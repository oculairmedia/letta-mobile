"""Fixed read-only mini fixture programs."""

REMOTE_AUDIT = """import json,pathlib,base64,hashlib
root=pathlib.Path('/opt/letta-test-server')
s=json.load(open(root/'bench-fixture/scope.json'))
agent=[]
for p in (root/'state/agents').iterdir():
 try:
  a=json.load(open(p))
  if a.get('id')==s['agent_id']: agent.append(a)
 except (ValueError,IsADirectoryError): pass
assert len(agent)==1 and agent[0]['name']=='mini-bench' and agent[0]['model']=='ollama/bench-model', 'fixture agent mismatch'
key=base64.urlsafe_b64encode(('conversation:'+s['conversation_id']).encode()).decode().rstrip('=')
c=json.load(open(root/'state/conversations'/key/'conversation.json'))
assert c['agent_id']==s['agent_id'] and c.get('model') in (None,'ollama/bench-model'), 'fixture conversation model override'
print(json.dumps(dict(**s,agentModel=agent[0]['model'],conversationModel=c.get('model'),conversationTitle=c.get('summary'),modelLogOffset=(root/'bench-fixture/model.log').stat().st_size,modelSha256=hashlib.sha256((root/'bench-fixture/model.cjs').read_bytes()).hexdigest())))
"""

REMOTE_TAIL = """import json,time,sys,os
parent=os.getppid()
p=open('/tmp/mini-iroh-wrapper.log');p.seek(0,2)
while os.getppid()==parent:
 line=p.readline()
 if line: print(json.dumps({'wallMs':time.time_ns()/1000000,'line':line.rstrip()}),flush=True)
 else: time.sleep(.01)
"""

CLOCK = "import sys,time\nfor line in sys.stdin: print(time.time_ns()/1000000,flush=True)"
