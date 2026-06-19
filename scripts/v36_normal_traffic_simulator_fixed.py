#!/usr/bin/env python3
"""
v36_normal_traffic_simulator_fixed.py
=====================================
Fixed V3.6 normal live audit-trail simulator.

Main fixes:
- realistic session start: normal sessions start with Connexion/SSO, never logout or forgot-password actions
- no duplicated event_id/record_id inside a run
- logout/SSO disconnect only appended as final event according to hybrid policy
- default timestamps use current local time for live dashboards
- uses backend-apis-actions.json and actions_order-v2.json for action enrichment
"""
from __future__ import annotations
import argparse, json, math, random, re, sys, time, uuid
from collections import defaultdict, Counter
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Tuple, Optional
try:
    from kafka import KafkaProducer
except Exception:
    KafkaProducer = None

DEFAULT_BACKEND_APIS = Path(__file__).with_name('backend-apis-actions.json')
DEFAULT_ACTIONS_ORDER = Path(__file__).with_name('actions_order-v2.json')
ENV_IDS = ['prod-espace-assure-01','prod-espace-assure-02','prod-espace-assure-03','preprod-espace-assure-01']
REGIONS = [('FR','IDF'),('FR','ARA'),('FR','NAQ'),('FR','OCC'),('FR','HDF'),('FR','PAC'),('FR','GES'),('FR','PDL'),('FR','BRE'),('FR','NOR')]
REGION_W = [.19,.12,.09,.09,.09,.08,.08,.06,.05,.05]
SESSION_END_ACTIONS = {'Déconnexion','Deconnexion','DÃ©connexion','SSO Disconnect'}
BAD_FIRST_FRONTENDS = {'load_logins','remove_from_spam','request_reset','submit_new_password','submit_identity','validate_creation','validate_token','explicit_logout','sso_disconnect'}
BAD_FIRST_VALUES = SESSION_END_ACTIONS | {'load_logins','remove_from_spam','Demande de réinitialisation de mot de passe','submit_new_password','submit_identity','Création de compte','validate_token'}
PERSONAS = {
 'power_user': {'w':.08,'aps':(10,24),'delay':(8,70),'hours':(7,23),'devices':{'mobile':.30,'desktop':.55,'tablet':.15}},
 'regular_user': {'w':.22,'aps':(5,12),'delay':(18,130),'hours':(8,20),'devices':{'mobile':.45,'desktop':.45,'tablet':.10}},
 'document_focused': {'w':.13,'aps':(5,11),'delay':(25,170),'hours':(9,18),'devices':{'mobile':.20,'desktop':.65,'tablet':.15}},
 'contact_heavy': {'w':.08,'aps':(5,10),'delay':(35,260),'hours':(9,19),'devices':{'mobile':.40,'desktop':.50,'tablet':.10}},
 'banking_focused': {'w':.06,'aps':(4,9),'delay':(35,180),'hours':(9,18),'devices':{'mobile':.25,'desktop':.60,'tablet':.15}},
 'beneficiary_manager': {'w':.07,'aps':(5,11),'delay':(25,150),'hours':(9,19),'devices':{'mobile':.40,'desktop':.50,'tablet':.10}},
 'security_conscious': {'w':.05,'aps':(4,9),'delay':(25,130),'hours':(8,22),'devices':{'mobile':.50,'desktop':.45,'tablet':.05}},
 'minimal_user': {'w':.16,'aps':(2,5),'delay':(45,300),'hours':(9,18),'devices':{'mobile':.55,'desktop':.35,'tablet':.10}},
 'mobile_only': {'w':.10,'aps':(3,8),'delay':(12,100),'hours':(7,23),'devices':{'mobile':.85,'desktop':.10,'tablet':.05}},
 'new_explorer': {'w':.05,'aps':(6,14),'delay':(12,75),'hours':(9,22),'devices':{'mobile':.45,'desktop':.45,'tablet':.10}},
}
TASK_FLOWS = {
 'document_flow':['home','documents','documents','requests','documents','home'],
 'refund_flow':['home','refunds','documents','refunds','home'],
 'beneficiary_flow':['home','beneficiaries','updatebeneficiaries','bankinginformation','home'],
 'profile_flow':['home','personalinformation','globalpreferences','home'],
 'support_flow':['home','help','requests','home'],
 'mobile_card_flow':['home','tpcarddownload','globalpreferences','home'],
 'explorer_flow':['home','documents','refunds','beneficiaries','personalinformation','requests','home'],
}
PERSONA_TASKS = {
 'document_focused':{'document_flow':.60,'refund_flow':.15,'support_flow':.10,'explorer_flow':.15},
 'banking_focused':{'beneficiary_flow':.55,'profile_flow':.15,'support_flow':.10,'explorer_flow':.20},
 'beneficiary_manager':{'beneficiary_flow':.65,'document_flow':.10,'profile_flow':.10,'explorer_flow':.15},
 'contact_heavy':{'support_flow':.60,'document_flow':.15,'refund_flow':.10,'explorer_flow':.15},
 'mobile_only':{'mobile_card_flow':.35,'refund_flow':.20,'document_flow':.15,'explorer_flow':.30},
 'minimal_user':{'refund_flow':.30,'document_flow':.25,'profile_flow':.15,'explorer_flow':.30},
 'security_conscious':{'profile_flow':.45,'beneficiary_flow':.20,'document_flow':.15,'explorer_flow':.20},
 'power_user':{'document_flow':.25,'refund_flow':.20,'beneficiary_flow':.20,'support_flow':.10,'explorer_flow':.25},
 'regular_user':{'refund_flow':.25,'document_flow':.25,'support_flow':.15,'profile_flow':.10,'explorer_flow':.25},
 'new_explorer':{'explorer_flow':.55,'document_flow':.15,'refund_flow':.15,'beneficiary_flow':.10,'support_flow':.05},
}

def now_local() -> datetime: return datetime.now().astimezone().replace(microsecond=0)
def parse_dt(s:str)->datetime:
    if s.lower() == 'now': return now_local()
    dt=datetime.fromisoformat(s); return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
def iso_ms(dt:datetime)->str: return dt.isoformat(timespec='milliseconds')
def load_json(p:Path)->Any:
    with p.open(encoding='utf-8') as f: return json.load(f)
def wchoice(rng:random.Random,d:Dict[str,float])->str: return rng.choices(list(d), weights=list(d.values()), k=1)[0]
def norm_page(x:Any)->str:
    s=str(x or 'unknown').strip().lower().replace(' ','_')
    return {'personal_info':'personalinformation','banking_info':'bankinginformation','preferences':'globalpreferences','tp_card_download':'tpcarddownload'}.get(s,s)
def strip_type(v:Any)->str:
    if not v: return 'UNTRACKED'
    s=str(v); return s.split('.')[-1] if '.' in s else s
def api_family(path:str)->str:
    parts=[p for p in str(path).split('?',1)[0].split('/') if p]
    if not parts: return 'root'
    if parts[0].lower() in {'v1','v2'} and len(parts)>1: return parts[1].lower().replace('-','_')
    return parts[0].lower().replace('-','_')
def build_lookup(backend:List[Dict[str,Any]])->Dict[str,Dict[str,Any]]:
    out={}
    for e in backend:
        m=str(e.get('method') or 'GET').upper(); p=str(e.get('path') or '/')
        tr=e.get('actionTracking') or {}
        out[f'{m} {p}']={'type':strip_type(tr.get('type')),'subType':tr.get('subType') or 'NO_SUBTYPE','value':tr.get('value'), 'controller':e.get('controller') or 'UNKNOWN_CONTROLLER','api_template':p,'api_family':api_family(p)}
    return out
def match_api(lookup, method, path):
    key=f'{method.upper()} {path}'
    if key in lookup: return dict(lookup[key])
    for k,v in lookup.items():
        km,p=k.split(' ',1)
        if km!=method.upper(): continue
        pat=re.escape(p).replace(r'\*', r'[^/]+')
        pat=re.sub(r'\\\{[^}]+\\\}', r'[^/]+', pat)
        if re.match('^'+pat+'$',path): return dict(v)
    return {'type':'UNTRACKED','subType':'NO_SUBTYPE','value':None,'controller':'UNKNOWN_CONTROLLER','api_template':path,'api_family':api_family(path)}
def enrich(m,api,front,lookup):
    tr=match_api(lookup,m,api); sub=tr.get('subType') or 'NO_SUBTYPE'
    val=tr.get('value') or (sub if sub!='NO_SUBTYPE' else front) or f'{m} {api}'
    tr.update({'frontend_action_name':front,'action_value':str(val),'subType':sub,'api_template':tr.get('api_template') or api,'api_family':tr.get('api_family') or api_family(api),'controller':tr.get('controller') or 'UNKNOWN_CONTROLLER'})
    return tr
def extract_pages(nav,lookup):
    pages=defaultdict(list)
    def add(page,m,api,name): pages[norm_page(page)].append((m.upper(),api,name,enrich(m,api,name,lookup)))
    for route,info in (nav.get('routes') or {}).items():
        for act in info.get('user_actions',[]) or []:
            for spec in act.get('api',[]) or []:
                m,p=spec.split(' ',1); add(route,m,p,act.get('name',route))
    for gate in (nav.get('post_login_gates') or {}).values():
        for spec in gate.get('api',[]) or []:
            m,p=spec.split(' ',1); add('home',m,p,gate.get('action') or gate.get('route') or 'home_gate')
        for act in gate.get('actions',[]) or []:
            for spec in act.get('api',[]) or []:
                m,p=spec.split(' ',1); add('home',m,p,act.get('name','home_gate'))
    return dict(pages)
def pick_device(rng, probs):
    d=wchoice(rng,probs)
    if d=='mobile': b=rng.choices(['chrome','safari'],weights=[.6,.4])[0]; o='android' if b=='chrome' else 'ios'
    elif d=='tablet': b=rng.choices(['safari','chrome'],weights=[.6,.4])[0]; o='ios' if b=='safari' else 'android'
    else: b=rng.choices(['chrome','firefox','edge','safari'],weights=[.6,.15,.15,.1])[0]; o='macos' if b=='safari' else 'windows'
    return d,b,o
def gen_ip(rng,country): return f"82.{rng.randint(120,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}"
def status(code): return 'SUCCESS' if 200<=code<300 or code==304 else ('PENDING' if code in {202,206,208} else 'FAILURE')
def req_size(rng,m,api):
    low=api.lower()
    if 'upload' in low: return rng.randint(10000,500000)
    if m=='GET': return rng.randint(4,120)
    if m=='POST': return rng.randint(80,2200)
    if m=='PUT': return rng.randint(60,1600)
    return rng.randint(10,600)
def resp_size(rng,m,api,code):
    if code>=400: return rng.randint(50,700)
    low=api.lower()
    if any(k in low for k in ['file','download','refund-resume','tp-card','adhesion-certificate','payment-schedules']): return rng.randint(10000,5000000)
    if 'all-info' in low: return rng.randint(2000,50000)
    if 'auth' in low or 'login' in low: return rng.randint(500,2000)
    return rng.randint(200,5000)
def http_code(rng,m):
    if rng.random()>.985: return rng.choice([400,401,403,404,422,429,500,503])
    if m=='POST': return rng.choices([200,201,204],weights=[.7,.25,.05])[0]
    if m in {'PUT','DELETE'}: return rng.choices([200,204],weights=[.75,.25])[0]
    return rng.choices([200,304],weights=[.96,.04])[0]
@dataclass
class User: insured_id:str; persona:str; device:str; browser:str; os:str; country:str; region:str; ip:str; envs:List[str]; sessions:int=0
@dataclass
class Session:
    user:User; sid:str; events:List[Dict[str,Any]]; idx:int=0
    @property
    def done(self): return self.idx>=len(self.events)
    @property
    def next(self): return self.events[self.idx]
class Clock:
    def __init__(self,start,speed): self.current=start; self.speed=speed; self.last=time.monotonic()
    def tick(self):
        now=time.monotonic(); self.current += timedelta(seconds=(now-self.last)*self.speed); self.last=now; return self.current
    def load_factor(self):
        m=self.current.hour*60+self.current.minute; v=.18
        for peak,width in [(510,90),(750,70),(1080,90)]: v += .48*math.exp(-((m-peak)**2)/(2*width**2))
        if self.current.weekday()>=5: v*=.6
        return min(1.0,max(.08,v))
class Simulator:
    def __init__(self,args):
        self.args=args; self.rng=random.Random(args.seed); self.lookup=build_lookup(load_json(Path(args.backend_apis))); self.pages=extract_pages(load_json(Path(args.actions_order)),self.lookup)
        self.users=self.build_users(args.insured_count); self.clock=Clock(parse_dt(args.virtual_day_start), args.virtual_speed); self.active=[]; self.counter=0; self.first_actions=Counter(); self.bad_first=0
        self.producer=None if args.dry_run else self.build_producer()
    def build_producer(self):
        if KafkaProducer is None: raise RuntimeError('Install kafka-python or use --dry-run')
        return KafkaProducer(bootstrap_servers=[s.strip() for s in self.args.bootstrap_servers.split(',')], value_serializer=lambda v: json.dumps(v,ensure_ascii=True).encode(), key_serializer=lambda v: v.encode() if v else None, acks=self.args.acks)
    def build_users(self,n):
        out=[]
        for i in range(n):
            persona=wchoice(self.rng,{k:v['w'] for k,v in PERSONAS.items()}); p=PERSONAS[persona]; d,b,o=pick_device(self.rng,p['devices']); (cc,rg)=self.rng.choices(REGIONS,weights=REGION_W,k=1)[0]
            out.append(User(f'insured-{i:05d}-{uuid.uuid4().hex[:6]}',persona,d,b,o,cc,rg,gen_ip(self.rng,cc),self.rng.sample(ENV_IDS,k=1)))
        return out
    def make_event(self,u,sid,ts,m,api,tr,code,page,seq,prev,start,is_anom=0,atype='normal'):
        self.counter += 1
        return {'record_id':f'evt-{self.counter:012d}','insured_id':u.insured_id,'session_id':sid,'timestamp':iso_ms(ts),'date':ts.strftime('%Y-%m-%d'),'hour':ts.hour,'day_of_week':ts.weekday(),'month':ts.month,'is_weekend':int(ts.weekday()>=5),'is_business_hours':int(8<=ts.hour<19),'http_method':m,'action_api':api,'api_template':tr.get('api_template') or api,'api_family':tr.get('api_family') or api_family(api),'controller':tr.get('controller') or 'UNKNOWN_CONTROLLER','frontend_action_name':tr.get('frontend_action_name') or 'unknown_action','action_value':tr.get('action_value') or tr.get('value') or tr.get('subType') or tr.get('frontend_action_name') or f'{m} {api}','action_type':tr.get('type') or 'UNTRACKED','action_subtype':tr.get('subType') or 'NO_SUBTYPE','http_code':int(code),'status':status(int(code)),'page':norm_page(page),'device':u.device,'browser':u.browser,'os':u.os,'user_agent':f'Mozilla/5.0 {u.device}/{u.os}/{u.browser}','ip':u.ip,'ip_country':u.country,'ip_region':u.region,'environment_id':self.rng.choice(u.envs),'session_action_seq':seq,'time_since_prev_action_ms':int((ts-prev).total_seconds()*1000) if prev else 0,'session_duration_so_far_ms':int((ts-start).total_seconds()*1000),'request_data_size_bytes':req_size(self.rng,m,api),'response_data_size_bytes':resp_size(self.rng,m,api,int(code)),'is_anomaly':is_anom,'anomaly_type':atype}
    def forced(self,m,api,front): return enrich(m,api,front,self.lookup)
    def choose_start(self):
        if self.args.auth_start_mode == 'legacy': return None
        r=self.rng.random()
        if r < .90: return 'POST','/auth/login','submit_credentials','login'
        # only use SSO if it exists in catalog, otherwise fall back to normal login
        if r < .98:
            for api in ['/auth/sso','/auth/login/sso','/auth/sso/login']:
                if f'GET {api}' in self.lookup or f'POST {api}' in self.lookup:
                    m='GET' if f'GET {api}' in self.lookup else 'POST'; return m,api,'sso_login','login'
        for api in ['/auth/activate-account/affiliation','/auth/login/create-account']:
            if f'POST {api}' in self.lookup: return 'POST',api,'activate_account','login'
        return 'POST','/auth/login','submit_credentials','login'
    def choose_task(self,p): return wchoice(self.rng, PERSONA_TASKS.get(p,{'explorer_flow':1}))
    def candidate_pages(self): return [p for p in self.pages.keys() if p not in {'login','mfa'}]
    def should_end(self):
        if self.args.no_session_end or self.args.session_end_policy=='none': return False
        if self.args.session_end_policy=='always': return True
        return self.rng.random()<self.args.session_end_probability
    def end_action(self):
        if self.args.session_end_mode=='logout': return 'GET','/auth/logout','explicit_logout','logout'
        if self.args.session_end_mode=='sso': return 'GET','/auth/sso/disconnect','sso_disconnect','logout'
        return ('GET','/auth/sso/disconnect','sso_disconnect','logout') if self.rng.random()<self.args.sso_disconnect_ratio else ('GET','/auth/logout','explicit_logout','logout')
    def append_end(self,u,sid,events):
        if not events: return
        last=events[-1]; prev=parse_dt(last['timestamp']); start=parse_dt(events[0]['timestamp']); m,api,front,page=self.end_action(); tr=self.forced(m,api,front); ts=prev+timedelta(seconds=self.rng.randint(self.args.session_end_delay_min,self.args.session_end_delay_max)); seq=int(last['session_action_seq'])+1
        ev=self.make_event(u,sid,ts,m,api,tr,200,page,seq,prev,start,0,'normal'); events.append(ev)
    def validate_first(self,events):
        if not events: return False
        first=events[0]; val=first.get('action_value'); front=first.get('frontend_action_name')
        ok = val not in BAD_FIRST_VALUES and front not in BAD_FIRST_FRONTENDS and val not in SESSION_END_ACTIONS
        self.first_actions[str(val)] += 1
        if not ok: self.bad_first += 1
        if self.args.strict_validate and not ok:
            raise RuntimeError(f'Bad first action: {val}/{front}')
        return ok
    def plan_session(self,u,start):
        for attempt in range(5):
            events=[]; u.sessions+=1; sid=f'sess-{u.insured_id}-{u.sessions:04d}-{uuid.uuid4().hex[:6]}'; p=PERSONAS[u.persona]; prev=None; seq=1; ts=start
            m,api,front,page = self.choose_start() or ('POST','/auth/login','submit_credentials','login')
            tr=self.forced(m,api,front); events.append(self.make_event(u,sid,ts,m,api,tr,200,page,seq,prev,start)); prev=ts; seq+=1
            if self.rng.random()<self.args.mfa_probability:
                # MFA only after successful login
                api='/auth/mfa/validate'; m='POST'; front='validate_mfa'; tr=self.forced(m,api,front); ts=prev+timedelta(seconds=self.rng.randint(20,90)); events.append(self.make_event(u,sid,ts,m,api,tr,200,'mfa',seq,prev,start)); prev=ts; seq+=1
            flow=[x for x in TASK_FLOWS[self.choose_task(u.persona)] if x in self.pages] or self.candidate_pages()
            aps=self.rng.randint(*p['aps'])
            for i in range(max(1,aps-seq+1)):
                page=flow[i%len(flow)] if flow and self.rng.random()<.9 else self.rng.choice(flow or self.candidate_pages())
                actions=[a for a in self.pages.get(page,[]) if a[3].get('action_value') not in SESSION_END_ACTIONS]
                if not actions: continue
                m,api,front,tr=self.rng.choice(actions); ts=prev+timedelta(seconds=self.rng.randint(*p['delay'])); code=http_code(self.rng,m); events.append(self.make_event(u,sid,ts,m,api,tr,code,page,seq,prev,start)); prev=ts; seq+=1
                if self.rng.random()<self.args.heartbeat_probability:
                    tr=self.forced('GET','/heartbeat','heartbeat'); ts=prev+timedelta(seconds=self.rng.randint(30,180)); ev=self.make_event(u,sid,ts,'GET','/heartbeat',tr,200,'global',seq,prev,start); ev['request_data_size_bytes']=10; ev['response_data_size_bytes']=30; events.append(ev); prev=ts; seq+=1
            if self.should_end(): self.append_end(u,sid,events)
            if self.validate_first(events): return Session(u,sid,events)
        return Session(u,sid,events)
    def start_session(self):
        busy={s.user.insured_id for s in self.active}; c=[u for u in self.users if u.insured_id not in busy]
        if not c: return
        u=self.rng.choice(c); p=PERSONAS[u.persona]; start=self.clock.current
        if self.args.respect_persona_hours and not (p['hours'][0] <= start.hour <= p['hours'][1]): start=start.replace(hour=self.rng.randint(p['hours'][0],min(23,p['hours'][1])), minute=self.rng.randint(0,59), second=self.rng.randint(0,59))
        self.active.append(self.plan_session(u,start))
    def emit(self,e,u):
        if self.args.dry_run: print(json.dumps(e,ensure_ascii=False))
        else: self.producer.send(self.args.topic,key=u.insured_id,value=e)
        if self.args.verbose: print(f"[{e['timestamp']}] NORMAL {e['action_value']} {e['session_id']}", flush=True)
    def run(self):
        print(f'V3.6 fixed normal simulator start={self.clock.current} speed={self.clock.speed}x', file=sys.stderr)
        wall=time.monotonic()
        try:
            while True:
                now=self.clock.tick(); target=max(1,int(self.args.insured_count*self.clock.load_factor()*self.args.concurrency_factor))
                while len(self.active)<target: self.start_session()
                rem=[]
                for s in self.active:
                    while not s.done and parse_dt(s.next['timestamp']) <= now:
                        self.emit(s.next,s.user); s.idx += 1
                    if not s.done: rem.append(s)
                self.active=rem
                if self.args.duration>0 and time.monotonic()-wall>=self.args.duration: break
                time.sleep(.01)
        finally:
            if self.producer: self.producer.flush(); self.producer.close()
            if self.args.print_summary:
                print('First action distribution:', dict(self.first_actions.most_common(20)), file=sys.stderr)
                print(f'Bad first actions observed: {self.bad_first}', file=sys.stderr)

def main():
    p=argparse.ArgumentParser(description='Fixed V3.6 normal live audit-trail simulator')
    p.add_argument('--bootstrap-servers',default='localhost:9092'); p.add_argument('--topic',default='topic-audit-trail'); p.add_argument('--backend-apis',default=str(DEFAULT_BACKEND_APIS)); p.add_argument('--actions-order',default=str(DEFAULT_ACTIONS_ORDER)); p.add_argument('--insured-count',type=int,default=80); p.add_argument('--virtual-day-start',default='now',help='ISO timestamp or now; default now for live dashboards'); p.add_argument('--virtual-speed',type=float,default=30); p.add_argument('--duration',type=int,default=0); p.add_argument('--dry-run',action='store_true'); p.add_argument('--acks',type=int,default=1); p.add_argument('--seed',type=int,default=42); p.add_argument('--verbose',action='store_true'); p.add_argument('--concurrency-factor',type=float,default=.20); p.add_argument('--heartbeat-probability',type=float,default=.04); p.add_argument('--mfa-probability',type=float,default=.07); p.add_argument('--respect-persona-hours',action='store_true')
    p.add_argument('--auth-start-mode',choices=['realistic','legacy'],default='realistic'); p.add_argument('--strict-validate',action='store_true'); p.add_argument('--print-summary',action='store_true')
    p.add_argument('--session-end-policy',choices=['none','always','probabilistic'],default='probabilistic'); p.add_argument('--session-end-probability',type=float,default=.80); p.add_argument('--session-end-mode',choices=['logout','sso','mixed'],default='mixed'); p.add_argument('--sso-disconnect-ratio',type=float,default=.15); p.add_argument('--session-end-delay-min',type=int,default=5); p.add_argument('--session-end-delay-max',type=int,default=60); p.add_argument('--no-session-end',action='store_true')
    Simulator(p.parse_args()).run()
if __name__ == '__main__': main()
