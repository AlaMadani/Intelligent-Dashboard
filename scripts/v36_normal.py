#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, random, re, sys, time, uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
try:
    from kafka import KafkaProducer
except Exception:
    KafkaProducer = None

ENV_IDS=['prod-espace-assure-01','prod-espace-assure-02','prod-espace-assure-03','preprod-espace-assure-01']
REGIONS=[('FR','IDF'),('FR','ARA'),('FR','NAQ'),('FR','OCC'),('FR','HDF'),('FR','PAC'),('FR','GES'),('FR','PDL'),('FR','BRE'),('FR','NOR')]
REGION_W=[.19,.12,.09,.09,.09,.08,.08,.06,.05,.05]
FOREIGN=[('MA','CAS'),('TN','TUN'),('BR','SP'),('NG','LA'),('RU','MOW'),('CN','BJ')]
PERSONAS={
'power_user':{'w':.08,'aps':(10,24),'delay':(8,70),'hours':(7,23),'devices':{'mobile':.30,'desktop':.55,'tablet':.15}},
'regular_user':{'w':.22,'aps':(5,12),'delay':(18,130),'hours':(8,20),'devices':{'mobile':.45,'desktop':.45,'tablet':.10}},
'document_focused':{'w':.13,'aps':(5,11),'delay':(25,170),'hours':(9,18),'devices':{'mobile':.20,'desktop':.65,'tablet':.15}},
'contact_heavy':{'w':.08,'aps':(5,10),'delay':(35,260),'hours':(9,19),'devices':{'mobile':.40,'desktop':.50,'tablet':.10}},
'banking_focused':{'w':.06,'aps':(4,9),'delay':(35,180),'hours':(9,18),'devices':{'mobile':.25,'desktop':.60,'tablet':.15}},
'beneficiary_manager':{'w':.07,'aps':(5,11),'delay':(25,150),'hours':(9,19),'devices':{'mobile':.40,'desktop':.50,'tablet':.10}},
'security_conscious':{'w':.05,'aps':(4,9),'delay':(25,130),'hours':(8,22),'devices':{'mobile':.50,'desktop':.45,'tablet':.05}},
'minimal_user':{'w':.16,'aps':(2,5),'delay':(45,300),'hours':(9,18),'devices':{'mobile':.55,'desktop':.35,'tablet':.10}},
'mobile_only':{'w':.10,'aps':(3,8),'delay':(12,100),'hours':(7,23),'devices':{'mobile':.85,'desktop':.10,'tablet':.05}},
'new_explorer':{'w':.05,'aps':(6,14),'delay':(12,75),'hours':(9,22),'devices':{'mobile':.45,'desktop':.45,'tablet':.10}}}
FLOWS={
'document_focused':['home','documents','documents','requests','documents','home'],
'banking_focused':['home','beneficiaries','updatebeneficiaries','bankinginformation','home'],
'beneficiary_manager':['home','beneficiaries','updatebeneficiaries','bankinginformation','home'],
'contact_heavy':['home','help','requests','home'], 'mobile_only':['home','tpcarddownload','globalpreferences','home'],
'security_conscious':['home','personalinformation','globalpreferences','home'],
'power_user':['home','documents','refunds','beneficiaries','requests','home'],
'regular_user':['home','refunds','documents','requests','home'], 'minimal_user':['home','refunds','home'],
'new_explorer':['home','documents','refunds','beneficiaries','personalinformation','requests','home']}

def now_local(): return datetime.now().astimezone().replace(microsecond=0)
def parse_dt(value):
    if str(value).lower()=='now': return now_local()
    dt=datetime.fromisoformat(value); return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
def load_json(path):
    with Path(path).open(encoding='utf-8') as f: return json.load(f)
def norm_page(value):
    s=str(value or 'unknown').strip().lower().replace(' ','_')
    return {'personal_info':'personalinformation','banking_info':'bankinginformation','preferences':'globalpreferences','tp_card_download':'tpcarddownload'}.get(s,s)
def strip_type(value): return str(value).split('.')[-1] if value else 'UNTRACKED'
def api_family(path):
    parts=[p for p in str(path).split('?',1)[0].split('/') if p]
    return ((parts[1] if parts[0].lower() in ('v1','v2') and len(parts)>1 else parts[0]) if parts else 'root').lower().replace('-','_')
def build_lookup(items):
    out={}
    for e in items:
        method=str(e.get('method') or 'GET').upper(); path=str(e.get('path') or '/'); tr=e.get('actionTracking') or {}
        out[(method,path)]={'action_type':strip_type(tr.get('type')),'action_subtype':tr.get('subType') or 'NO_SUBTYPE','action_value':tr.get('value'),'controller':e.get('controller') or 'UNKNOWN_CONTROLLER','api_template':path,'api_family':api_family(path)}
    return out
def match_api(lookup,method,path):
    method=method.upper()
    if (method,path) in lookup: return dict(lookup[(method,path)])
    bare=path.split('?',1)[0]
    if (method,bare) in lookup: return dict(lookup[(method,bare)])
    for (m,p),v in lookup.items():
        if m!=method: continue
        pattern=re.escape(p).replace(r'\*',r'[^/]+'); pattern=re.sub(r'\\\{[^}]+\\\}',r'[^/]+',pattern)
        if re.fullmatch(pattern,bare): return dict(v)
    return {'action_type':'UNTRACKED','action_subtype':'NO_SUBTYPE','action_value':None,'controller':'UNKNOWN_CONTROLLER','api_template':bare,'api_family':api_family(bare)}
def catalog(nav):
    pages={}
    for route,info in (nav.get('routes') or {}).items():
        values=[]
        for action in info.get('user_actions',[]) or []:
            for spec in action.get('api',[]) or []:
                method,path=spec.split(' ',1); values.append((method.upper(),path,action.get('name') or route))
        if values: pages[norm_page(route)]=values
    return pages
def weighted(rng,d): return rng.choices(list(d),weights=list(d.values()),k=1)[0]
def device(rng,persona):
    d=weighted(rng,PERSONAS[persona]['devices'])
    if d=='mobile': b=rng.choices(['chrome','safari'],[.6,.4])[0]; o='android' if b=='chrome' else 'ios'
    elif d=='tablet': b=rng.choices(['safari','chrome'],[.6,.4])[0]; o='ios' if b=='safari' else 'android'
    else: b=rng.choices(['chrome','firefox','edge','safari'],[.6,.15,.15,.1])[0]; o='macos' if b=='safari' else 'windows'
    return d,b,o
def make_ip(rng,country):
    first=82 if country=='FR' else rng.choice([45,91,103,185]); return f'{first}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}'
@dataclass
class User:
    insured_id:str; persona:str; device:str; browser:str; os:str; country:str; region:str; ip:str; env:str; sessions:int=0

def make_users(rng,count):
    users=[]; weights={k:v['w'] for k,v in PERSONAS.items()}
    for i in range(count):
        persona=weighted(rng,weights); d,b,o=device(rng,persona); country,region=rng.choices(REGIONS,weights=REGION_W,k=1)[0]
        users.append(User(f'insured-{i:05d}-{uuid.uuid4().hex[:6]}',persona,d,b,o,country,region,make_ip(rng,country),rng.choice(ENV_IDS)))
    return users
def sizes(rng,method,path,code):
    request=rng.randint(4,120) if method=='GET' else rng.randint(60,2200)
    if 'upload' in path.lower(): request=rng.randint(10000,500000)
    response=rng.randint(50,700) if code>=400 else rng.randint(200,5000)
    if any(x in path.lower() for x in ('file','download','refund-resume','tp-card','adhesion-certificate','payment-schedules')): response=rng.randint(10000,5000000)
    return request,response
def make_event(user,sid,seq,ts,prev,start,method,path,page,front,lookup,rng,code=200,is_anomaly=0,anomaly_type=''):
    tr=match_api(lookup,method,path); req,res=sizes(rng,method,path,code)
    status='SUCCESS' if 200<=code<300 or code==304 else ('PENDING' if code in (202,206,208) else 'FAILURE')
    return {'record_id':str(uuid.uuid4()),'event_id':str(uuid.uuid4()),'insured_id':user.insured_id,'session_id':sid,'timestamp':ts.isoformat(timespec='milliseconds'),'date':ts.strftime('%Y-%m-%d'),'hour':ts.hour,'day_of_week':ts.weekday(),'month':ts.month,'is_weekend':int(ts.weekday()>=5),'is_business_hours':int(8<=ts.hour<19),'http_method':method,'action_api':path,'api_template':tr['api_template'],'api_family':tr['api_family'],'controller':tr['controller'],'frontend_action_name':front,'action_value':tr.get('action_value') or (tr['action_subtype'] if tr['action_subtype']!='NO_SUBTYPE' else front),'action_type':tr['action_type'],'action_subtype':tr['action_subtype'],'http_code':int(code),'status':status,'page':norm_page(page),'device':user.device,'browser':user.browser,'os':user.os,'user_agent':f'Mozilla/5.0 {user.device}/{user.os}/{user.browser}','ip':user.ip,'ip_country':user.country,'ip_region':user.region,'environment_id':user.env,'session_action_seq':seq,'time_since_prev_action_ms':int((ts-prev).total_seconds()*1000) if prev else 0,'session_duration_so_far_ms':int((ts-start).total_seconds()*1000),'request_data_size_bytes':req,'response_data_size_bytes':res,'is_anomaly':is_anomaly,'anomaly_type':anomaly_type}

class KafkaSink:
    def __init__(self,args):
        self.args=args; self.topic=args.topic; self.producer=None; self.attempted=0; self.delivered=0; self.failed=0
        if args.dry_run: return
        if KafkaProducer is None: raise RuntimeError('Install kafka-python: pip install kafka-python')
        servers=[s.strip() for s in args.bootstrap_servers.split(',') if s.strip()]
        try:
            self.producer=KafkaProducer(bootstrap_servers=servers,acks=int(args.acks),key_serializer=lambda x:str(x).encode('utf-8'),value_serializer=lambda x:json.dumps(x,ensure_ascii=False,separators=(',',':')).encode('utf-8'),api_version_auto_timeout_ms=int(args.kafka_startup_timeout*1000),request_timeout_ms=int(args.kafka_request_timeout*1000),max_block_ms=int(args.kafka_startup_timeout*1000),retries=args.kafka_retries,retry_backoff_ms=500,linger_ms=0)
        except Exception as exc: raise RuntimeError(f'Kafka connection failed for {servers}: {exc}') from exc
        if not self.producer.bootstrap_connected():
            self.producer.close(timeout=0); self.producer=None
            raise RuntimeError(f'Kafka bootstrap failed for {servers}; verify broker and advertised.listeners')
        print(f'Kafka connected: servers={servers} topic={self.topic} acks={args.acks}',file=sys.stderr,flush=True)
    def send(self,event):
        self.attempted+=1
        if self.args.dry_run:
            if self.args.verbose: print(json.dumps(event,ensure_ascii=False),flush=True)
            self.delivered+=1; return
        try:
            meta=self.producer.send(self.topic,key=event['insured_id'],value=event).get(timeout=self.args.kafka_send_timeout)
            self.delivered+=1
            if self.args.verbose: print(f"[KAFKA DELIVERED] record_id={event['record_id']} partition={meta.partition} offset={meta.offset}",file=sys.stderr,flush=True)
        except KeyboardInterrupt: raise
        except Exception as exc:
            self.failed+=1
            raise RuntimeError(f"Kafka did not confirm record {event['record_id']}: {type(exc).__name__}: {exc}") from exc
    def close(self):
        producer,self.producer=self.producer,None
        if producer is None: return
        try: producer.flush(timeout=self.args.kafka_flush_timeout)
        except Exception as exc: print(f'[KAFKA FLUSH WARNING] {exc}',file=sys.stderr,flush=True)
        finally:
            try: producer.close(timeout=1)
            except Exception: pass
        print(f'Kafka final: attempted={self.attempted} delivered={self.delivered} failed={self.failed}',file=sys.stderr,flush=True)

def add_common(p):
    p.add_argument('--bootstrap-servers',default='localhost:9092'); p.add_argument('--topic',default='topic-audit-trail'); p.add_argument('--backend-apis',default=str(Path(__file__).with_name('backend-apis-actions.json'))); p.add_argument('--actions-order',default=str(Path(__file__).with_name('actions_order-v2.json'))); p.add_argument('--insured-count',type=int,default=80); p.add_argument('--dry-run',action='store_true'); p.add_argument('--acks',type=int,choices=[-1,0,1],default=1); p.add_argument('--seed',type=int,default=42); p.add_argument('--verbose',action='store_true'); p.add_argument('--strict-validate',action='store_true'); p.add_argument('--print-summary',action='store_true'); p.add_argument('--session-end-policy',choices=['none','always','probabilistic','auto'],default='probabilistic'); p.add_argument('--session-end-probability',type=float,default=.8); p.add_argument('--session-end-mode',choices=['logout','sso','mixed'],default='mixed'); p.add_argument('--sso-disconnect-ratio',type=float,default=.15); p.add_argument('--session-end-delay-min',type=int,default=5); p.add_argument('--session-end-delay-max',type=int,default=60); p.add_argument('--no-session-end',action='store_true'); p.add_argument('--kafka-startup-timeout',type=float,default=10); p.add_argument('--kafka-request-timeout',type=float,default=15); p.add_argument('--kafka-send-timeout',type=float,default=15); p.add_argument('--kafka-flush-timeout',type=float,default=10); p.add_argument('--kafka-retries',type=int,default=3)
def should_end(args,rng): return not args.no_session_end and args.session_end_policy!='none' and (args.session_end_policy=='always' or rng.random()<args.session_end_probability)
def end_spec(args,rng):
    sso=args.session_end_mode=='sso' or (args.session_end_mode=='mixed' and rng.random()<args.sso_disconnect_ratio)
    return ('GET','/auth/sso/disconnect','logout','sso_disconnect') if sso else ('GET','/auth/logout','logout','explicit_logout')

def generate_session(user,start,args,rng,lookup,pages):
    user.sessions+=1; sid=f'sess-{user.insured_id}-{user.sessions:04d}-{uuid.uuid4().hex[:6]}'; events=[]; ts=start; prev=None; seq=1
    events.append(make_event(user,sid,seq,ts,prev,start,'POST','/auth/login','login','submit_credentials',lookup,rng,200)); prev=ts; seq+=1
    if rng.random()<args.mfa_probability:
        ts=prev+timedelta(seconds=rng.randint(20,90)); events.append(make_event(user,sid,seq,ts,prev,start,'POST','/auth/mfa/validate','mfa','validate_mfa',lookup,rng,200)); prev=ts; seq+=1
    flow=FLOWS[user.persona]; count=rng.randint(*PERSONAS[user.persona]['aps'])
    candidates=list(pages) or ['home']
    for i in range(max(1,count-seq+1)):
        page=flow[i%len(flow)] if rng.random()<.9 else rng.choice(candidates)
        actions=pages.get(page) or pages.get('home') or [('GET','/heartbeat','heartbeat')]
        method,path,front=rng.choice(actions); ts=prev+timedelta(seconds=rng.randint(*PERSONAS[user.persona]['delay'])); code=200 if rng.random()<.985 else rng.choice([400,401,403,404,422,429,500,503])
        events.append(make_event(user,sid,seq,ts,prev,start,method,path,page,front,lookup,rng,code)); prev=ts; seq+=1
        if rng.random()<args.heartbeat_probability:
            ts=prev+timedelta(seconds=rng.randint(30,180)); events.append(make_event(user,sid,seq,ts,prev,start,'GET','/heartbeat','global','heartbeat',lookup,rng,200)); prev=ts; seq+=1
    if should_end(args,rng):
        method,path,page,front=end_spec(args,rng); ts=prev+timedelta(seconds=rng.randint(args.session_end_delay_min,args.session_end_delay_max)); events.append(make_event(user,sid,seq,ts,prev,start,method,path,page,front,lookup,rng,200))
    return events

def main():
    p=argparse.ArgumentParser(description='Corrected V3.6 normal live simulator'); add_common(p)
    p.add_argument('--virtual-day-start',default='now'); p.add_argument('--virtual-speed',type=float,default=30); p.add_argument('--duration',type=float,default=0); p.add_argument('--concurrency-factor',type=float,default=.20); p.add_argument('--heartbeat-probability',type=float,default=.04); p.add_argument('--mfa-probability',type=float,default=.07); p.add_argument('--respect-persona-hours',action='store_true'); p.add_argument('--auth-start-mode',choices=['realistic','legacy'],default='realistic')
    args=p.parse_args(); rng=random.Random(args.seed); lookup=build_lookup(load_json(args.backend_apis)); pages=catalog(load_json(args.actions_order)); users=make_users(rng,args.insured_count); sink=KafkaSink(args); wall_start=time.monotonic(); virtual=parse_dt(args.virtual_day_start); sessions=0
    try:
        while args.duration==0 or time.monotonic()-wall_start<args.duration:
            user=users[sessions%len(users)]; start=virtual
            if args.respect_persona_hours:
                lo,hi=PERSONAS[user.persona]['hours']
                if not lo<=start.hour<=hi: start=start.replace(hour=rng.randint(lo,min(23,hi)),minute=rng.randint(0,59),second=rng.randint(0,59))
            events=generate_session(user,start,args,rng,lookup,pages)
            for event in events: sink.send(event)
            sessions+=1; virtual=max(parse_dt(events[-1]['timestamp']),virtual)+timedelta(seconds=1)
            time.sleep(max(.01,1/max(args.virtual_speed,.001)))
    except KeyboardInterrupt: print('\nShutdown requested.',file=sys.stderr,flush=True)
    finally:
        sink.close()
        if args.print_summary: print(json.dumps({'mode':'normal','sessions':sessions,'attempted':sink.attempted,'delivered':sink.delivered,'failed':sink.failed},indent=2),file=sys.stderr)
if __name__=='__main__': main()
