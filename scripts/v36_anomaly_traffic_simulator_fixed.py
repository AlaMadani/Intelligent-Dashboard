#!/usr/bin/env python3
"""
v36_anomaly_traffic_simulator_fixed.py
======================================
Fixed V3.6 anomaly simulator.

Fixes:
- every anomalous session starts with a valid login/Connexion event
- anomalous evidence uses real backend/catalog actions where possible
- logout/SSO disconnect appears only as optional final normal event
- default timestamps use current local time for live dashboards
- unique event_id/record_id per emitted event
"""
from __future__ import annotations
import argparse, json, random, re, sys, time, uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from itertools import cycle
from pathlib import Path
from typing import Any, Dict, List
try:
    from kafka import KafkaProducer
except Exception:
    KafkaProducer=None
DEFAULT_BACKEND_APIS=Path(__file__).with_name('backend-apis-actions.json')
DEFAULT_ACTIONS_ORDER=Path(__file__).with_name('actions_order-v2.json')
CANONICAL_TAGS=['credential_stuffing','session_hijacking','data_exfiltration','api_scraping','off_hours_compromise','behavioral_sequence_anomaly']
ALIASES={'repeated_fail':'credential_stuffing','distributed_brute_force':'credential_stuffing','geo_jump':'session_hijacking','unusual_hour':'off_hours_compromise','rapid_fire':'behavioral_sequence_anomaly','impossible_seq':'behavioral_sequence_anomaly'}
ENV_IDS=['prod-espace-assure-01','prod-espace-assure-02','prod-espace-assure-03','preprod-espace-assure-01']; REGIONS=[('FR','IDF'),('FR','ARA'),('FR','NAQ'),('FR','OCC'),('FR','HDF')]; FOREIGN=[('MA','CAS'),('TN','TUN'),('BR','SP'),('NG','LA'),('RU','MOW'),('CN','BJ')]
def now_local(): return datetime.now().astimezone().replace(microsecond=0)
def parse_dt(s):
    if s.lower()=='now': return now_local()
    dt=datetime.fromisoformat(s); return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
def iso_ms(dt): return dt.isoformat(timespec='milliseconds')
def load_json(p):
    with Path(p).open(encoding='utf-8') as f: return json.load(f)
def norm_page(x):
    s=str(x or 'unknown').strip().lower().replace(' ','_'); return {'personal_info':'personalinformation','banking_info':'bankinginformation','preferences':'globalpreferences','tp_card_download':'tpcarddownload'}.get(s,s)
def strip_type(v):
    if not v: return 'UNTRACKED'
    s=str(v); return s.split('.')[-1] if '.' in s else s
def api_family(path):
    parts=[p for p in str(path).split('?',1)[0].split('/') if p]
    if not parts: return 'root'
    if parts[0].lower() in {'v1','v2'} and len(parts)>1: return parts[1].lower().replace('-','_')
    return parts[0].lower().replace('-','_')
def build_lookup(backend):
    out={}
    for e in backend:
        m=str(e.get('method') or 'GET').upper(); p=str(e.get('path') or '/'); tr=e.get('actionTracking') or {}
        out[f'{m} {p}']={'type':strip_type(tr.get('type')),'subType':tr.get('subType') or 'NO_SUBTYPE','value':tr.get('value'),'controller':e.get('controller') or 'UNKNOWN_CONTROLLER','api_template':p,'api_family':api_family(p)}
    return out
def match_api(lookup,m,path):
    key=f'{m.upper()} {path}'
    if key in lookup: return dict(lookup[key])
    for k,v in lookup.items():
        km,p=k.split(' ',1)
        if km!=m.upper(): continue
        pat=re.escape(p).replace(r'\*', r'[^/]+'); pat=re.sub(r'\\\{[^}]+\\\}', r'[^/]+', pat)
        if re.match('^'+pat+'$',path): return dict(v)
    return {'type':'UNTRACKED','subType':'NO_SUBTYPE','value':None,'controller':'UNKNOWN_CONTROLLER','api_template':path,'api_family':api_family(path)}
def enrich(m,api,front,lookup):
    tr=match_api(lookup,m,api); sub=tr.get('subType') or 'NO_SUBTYPE'; val=tr.get('value') or (sub if sub!='NO_SUBTYPE' else front) or f'{m} {api}'
    tr.update({'frontend_action_name':front,'action_value':str(val),'subType':sub,'api_template':tr.get('api_template') or api,'api_family':tr.get('api_family') or api_family(api),'controller':tr.get('controller') or 'UNKNOWN_CONTROLLER'}); return tr
def gen_ip(rng,country): return f"82.{rng.randint(120,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}" if country=='FR' else f"{rng.choice(['185','91','45','103','196','197'])}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}"
def status(code): return 'SUCCESS' if 200<=code<300 or code==304 else ('PENDING' if code in {202,206,208} else 'FAILURE')
def req_size(rng,m,api): return rng.randint(10000,500000) if 'upload' in api.lower() else (rng.randint(4,120) if m=='GET' else rng.randint(80,2200))
def resp_size(rng,m,api,code):
    if code>=400: return rng.randint(50,900)
    return rng.randint(10000,5000000) if any(k in api.lower() for k in ['file','download','refund-resume','tp-card','adhesion-certificate','payment-schedules']) else rng.randint(200,50000)
@dataclass
class User: insured_id:str; country:str; region:str; device:str; browser:str; os:str; ip:str; envs:List[str]; sessions:int=0
@dataclass
class Session:
    user:User; sid:str; tag:str; events:List[Dict[str,Any]]; idx:int=0
    @property
    def done(self): return self.idx>=len(self.events)
    @property
    def next(self): return self.events[self.idx]
class Clock:
    def __init__(self,start,speed): self.current=start; self.speed=speed; self.last=time.monotonic()
    def tick(self): now=time.monotonic(); self.current += timedelta(seconds=(now-self.last)*self.speed); self.last=now; return self.current
class Simulator:
    def __init__(self,args):
        self.args=args; self.rng=random.Random(args.seed); self.lookup=build_lookup(load_json(args.backend_apis)); self.users=self.build_users(args.insured_count); self.clock=Clock(parse_dt(args.virtual_start),args.rate); self.active=[]; self.counter=0; self.completed=0; tags=CANONICAL_TAGS if args.tag=='all' else [ALIASES.get(args.tag,args.tag)]; self.tags=cycle(tags); self.producer=None if args.dry_run else self.build_producer()
    def build_producer(self):
        if KafkaProducer is None: raise RuntimeError('Install kafka-python or use --dry-run')
        return KafkaProducer(bootstrap_servers=[s.strip() for s in self.args.bootstrap_servers.split(',')],value_serializer=lambda v:json.dumps(v,ensure_ascii=True).encode(),key_serializer=lambda v:v.encode() if v else None,acks=self.args.acks)
    def build_users(self,n):
        out=[]
        for i in range(n):
            cc,rg=self.rng.choice(REGIONS); dev=self.rng.choice(['desktop','mobile','tablet']); br=self.rng.choice(['chrome','firefox','edge','safari']); osn='windows' if dev=='desktop' else self.rng.choice(['android','ios']); out.append(User(f'insured-anom-{i:05d}-{uuid.uuid4().hex[:6]}',cc,rg,dev,br,osn,gen_ip(self.rng,cc),self.rng.sample(ENV_IDS,k=1)))
        return out
    def make_event(self,u,sid,ts,m,api,tr,code,page,seq,prev,start,is_anom,atype):
        self.counter+=1
        return {'record_id':f'anom-{self.counter:012d}','insured_id':u.insured_id,'session_id':sid,'timestamp':iso_ms(ts),'date':ts.strftime('%Y-%m-%d'),'hour':ts.hour,'day_of_week':ts.weekday(),'month':ts.month,'is_weekend':int(ts.weekday()>=5),'is_business_hours':int(8<=ts.hour<19),'http_method':m,'action_api':api,'api_template':tr.get('api_template') or api,'api_family':tr.get('api_family') or api_family(api),'controller':tr.get('controller') or 'UNKNOWN_CONTROLLER','frontend_action_name':tr.get('frontend_action_name') or 'unknown_action','action_value':tr.get('action_value') or tr.get('value') or tr.get('subType') or tr.get('frontend_action_name') or f'{m} {api}','action_type':tr.get('type') or 'UNTRACKED','action_subtype':tr.get('subType') or 'NO_SUBTYPE','http_code':int(code),'status':status(int(code)),'page':norm_page(page),'device':u.device,'browser':u.browser,'os':u.os,'user_agent':f'Mozilla/5.0 {u.device}/{u.os}/{u.browser}','ip':u.ip,'ip_country':u.country,'ip_region':u.region,'environment_id':self.rng.choice(u.envs),'session_action_seq':seq,'time_since_prev_action_ms':int((ts-prev).total_seconds()*1000) if prev else 0,'session_duration_so_far_ms':int((ts-start).total_seconds()*1000),'request_data_size_bytes':req_size(self.rng,m,api),'response_data_size_bytes':resp_size(self.rng,m,api,code),'is_anomaly':is_anom,'anomaly_type':atype}
    def forced(self,m,api,front): return enrich(m,api,front,self.lookup)
    def login_event(self,u,sid,start,tag):
        tr=self.forced('POST','/auth/login','submit_credentials'); return self.make_event(u,sid,start,'POST','/auth/login',tr,200,'login',1,None,start,0,'normal')
    def end_prob(self,tag):
        if self.args.session_end_policy!='auto': return self.args.session_end_probability
        return {'credential_stuffing':.10,'api_scraping':.15,'data_exfiltration':.40,'session_hijacking':.50,'off_hours_compromise':.50,'behavioral_sequence_anomaly':.60}.get(tag,.40)
    def should_end(self,tag):
        if self.args.no_session_end or self.args.session_end_policy=='none': return False
        if self.args.session_end_policy=='always': return True
        return self.rng.random()<self.end_prob(tag)
    def end_action(self):
        if self.args.session_end_mode=='logout': return 'GET','/auth/logout','explicit_logout','logout'
        if self.args.session_end_mode=='sso': return 'GET','/auth/sso/disconnect','sso_disconnect','logout'
        return ('GET','/auth/sso/disconnect','sso_disconnect','logout') if self.rng.random()<self.args.sso_disconnect_ratio else ('GET','/auth/logout','explicit_logout','logout')
    def append_end(self,u,sid,events):
        if not events: return
        last=events[-1]; prev=parse_dt(last['timestamp']); start=parse_dt(events[0]['timestamp']); m,api,front,page=self.end_action(); tr=self.forced(m,api,front); ts=prev+timedelta(seconds=self.rng.randint(self.args.session_end_delay_min,self.args.session_end_delay_max)); seq=int(last['session_action_seq'])+1
        events.append(self.make_event(u,sid,ts,m,api,tr,200,page,seq,prev,start,0,'normal'))
    def anchor(self,tag):
        now=self.clock.current
        if tag=='off_hours_compromise':
            return now.replace(hour=self.rng.randint(2,4), minute=self.rng.randint(0,59), second=self.rng.randint(0,59))
        return now
    def plan(self,u,tag):
        u.sessions+=1; sid=f'sess-anom-{u.insured_id}-{u.sessions:04d}-{uuid.uuid4().hex[:6]}'; start=self.anchor(tag); events=[self.login_event(u,sid,start,tag)]; prev=start; seq=2
        def add(m,api,front,page,dt_ms=None,dt_s=None,code=200,large=False):
            nonlocal prev,seq
            ts=prev+(timedelta(milliseconds=dt_ms) if dt_ms is not None else timedelta(seconds=dt_s or self.rng.randint(5,60))); tr=self.forced(m,api,front); ev=self.make_event(u,sid,ts,m,api,tr,code,page,seq,prev,start,1,tag)
            if large: ev['response_data_size_bytes']=self.rng.randint(800000,12000000)
            if dt_ms is not None: ev['time_since_prev_action_ms']=dt_ms
            events.append(ev); prev=ts; seq+=1
        if tag=='credential_stuffing':
            # login success followed by abnormal repeated failed attempts in same synthetic session
            for _ in range(8+self.rng.randint(0,8)): add('POST','/auth/login','submit_credentials','login',dt_ms=self.rng.randint(40,180),code=self.rng.choice([401,401,403,429]))
        elif tag=='session_hijacking':
            add('GET','/all-info?actionKey=requests','refresh_requests','home',dt_s=30); add('GET','/documents','list_documents','documents',dt_s=30); cc,rg=self.rng.choice(FOREIGN); u.country,u.region,u.ip=cc,rg,gen_ip(self.rng,cc); u.device=self.rng.choice(['desktop','mobile']); u.browser=self.rng.choice(['chrome','edge','firefox']); u.os='windows' if u.device=='desktop' else 'android'; add('GET','/documents/file','download_document','documents',dt_s=8,large=True); add('POST','/insured/all-refund-resume','download_all_refunds_pdf','refunds',dt_s=10,large=True)
        elif tag=='data_exfiltration':
            choices=[('GET','/documents/file','download_document','documents'),('GET','/document-display/download/{id}','document_display_download','documents'),('POST','/insured/all-refund-resume','download_all_refunds_pdf','refunds'),('POST','/insured/refund-resume','download_refund_pdf','refunds')]
            for _ in range(12+self.rng.randint(0,8)): add(*self.rng.choice(choices),dt_ms=self.rng.randint(80,350),large=True)
        elif tag=='api_scraping':
            choices=[('GET','/documents','list_documents','documents'),('GET','/documents/file','download_document','documents'),('GET','/requests/messages/file','download_request_doc','requests'),('GET','/all-info?actionKey=requests','refresh_requests','requests')]
            for _ in range(18+self.rng.randint(0,15)): add(*self.rng.choice(choices),dt_ms=self.rng.randint(40,220))
        elif tag=='off_hours_compromise':
            cc,rg=self.rng.choice(FOREIGN); u.country,u.region,u.ip=cc,rg,gen_ip(self.rng,cc); choices=[('PUT','/insured/rib','update_rib','bankinginformation'),('POST','/insured/sign-file','sign_rib','bankinginformation'),('GET','/documents/file','download_document','documents'),('POST','/insured/all-refund-resume','download_all_refunds_pdf','refunds')]
            for _ in range(6+self.rng.randint(0,5)):
                add(*self.rng.choice(choices),dt_s=self.rng.randint(10,60),large=True); events[-1]['is_business_hours']=0
        else:
            weird=[('GET','/documents/file','download_document','documents'),('PUT','/insured/rib','update_rib','bankinginformation'),('POST','/v2/teletransmission/update/{amoCode}','teletransmission_update','globalpreferences'),('POST','/help/debug-reports','debug_report','help'),('GET','/insured/tp-card','download_tp_card_pdf','tpcarddownload'),('POST','/documents/upload-documents','upload_document','documents')]
            for i in range(10+self.rng.randint(0,8)): m,api,front,page=weird[i%len(weird)] if i<len(weird) else self.rng.choice(weird); add(m,api,front,page,dt_ms=self.rng.randint(100,900),code=self.rng.choices([200,200,401,403,429],weights=[.7,.1,.08,.06,.06])[0])
        if self.should_end(tag): self.append_end(u,sid,events)
        return Session(u,sid,tag,events)
    def start_session(self):
        busy={s.user.insured_id for s in self.active}; c=[u for u in self.users if u.insured_id not in busy]
        if c: self.active.append(self.plan(self.rng.choice(c),next(self.tags)))
    def emit(self,s,e):
        if self.args.dry_run: print(json.dumps(e,ensure_ascii=False))
        else: self.producer.send(self.args.topic,key=s.user.insured_id,value=e)
        if self.args.verbose: print(f"[{e['timestamp']}] ANOMALY {s.tag} {e['action_value']} end={e['is_anomaly']==0}", flush=True)
    def run(self):
        print(f'V3.6 fixed anomaly simulator tag={self.args.tag} start={self.clock.current}', file=sys.stderr)
        try:
            while self.args.session_count==0 or self.completed<self.args.session_count:
                now=self.clock.tick()
                while len(self.active)<self.args.concurrent_sessions and (self.args.session_count==0 or self.completed+len(self.active)<self.args.session_count): self.start_session()
                rem=[]
                for s in self.active:
                    while not s.done and parse_dt(s.next['timestamp'])<=now:
                        self.emit(s,s.next); s.idx+=1
                    if not s.done: rem.append(s)
                    else: self.completed+=1
                self.active=rem; time.sleep(.01)
        finally:
            if self.producer: self.producer.flush(); self.producer.close()
def main():
    p=argparse.ArgumentParser(description='Fixed V3.6 anomaly live audit-trail simulator')
    p.add_argument('--bootstrap-servers',default='localhost:9092'); p.add_argument('--topic',default='topic-audit-trail'); p.add_argument('--backend-apis',default=str(DEFAULT_BACKEND_APIS)); p.add_argument('--actions-order',default=str(DEFAULT_ACTIONS_ORDER)); p.add_argument('--tag',choices=['all']+CANONICAL_TAGS+sorted(ALIASES),required=True); p.add_argument('--session-count',type=int,default=0); p.add_argument('--concurrent-sessions',type=int,default=4); p.add_argument('--insured-count',type=int,default=100); p.add_argument('--rate',type=float,default=90); p.add_argument('--virtual-start',default='now'); p.add_argument('--dry-run',action='store_true'); p.add_argument('--acks',type=int,default=1); p.add_argument('--seed',type=int,default=42); p.add_argument('--verbose',action='store_true')
    p.add_argument('--session-end-policy',choices=['none','always','probabilistic','auto'],default='auto'); p.add_argument('--session-end-probability',type=float,default=.40); p.add_argument('--session-end-mode',choices=['logout','sso','mixed'],default='mixed'); p.add_argument('--sso-disconnect-ratio',type=float,default=.15); p.add_argument('--session-end-delay-min',type=int,default=5); p.add_argument('--session-end-delay-max',type=int,default=60); p.add_argument('--no-session-end',action='store_true')
    Simulator(p.parse_args()).run()
if __name__=='__main__': main()
