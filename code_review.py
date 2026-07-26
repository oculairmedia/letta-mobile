import urllib.request
import json
import os

diff = os.popen('cd android-compose && git diff main').read()
try:
    req = urllib.request.Request('http://127.0.0.1:8080/review', data=json.dumps({'content': diff}).encode(), headers={'Content-Type': 'application/json'})
    response = urllib.request.urlopen(req, timeout=5)
    print(json.loads(response.read().decode())['feedback'])
except Exception as e:
    print(f"Error: {e}")
