#!/usr/bin/python

import json
import requests
import time
import random 

api = 'http://localhost:8000'
headers = {'Content-Type': 'application/vnd.api+json', 'Accept': 'application/vnd.api+json'}

def post(path, payload={}) :
  r = requests.post(api+path, data=json.dumps(payload), headers=headers)
  if (r.text):
    print json.dumps(json.loads(r.text), indent=2)

def patch(path, payload={}) :
  r = requests.patch(api+path, data=json.dumps(payload), headers=headers)
  if (r.text):
    print json.dumps(json.loads(r.text), indent=2)

def get(path) :
  r = requests.get(api+path, headers=headers)
  if (r.text):
    print json.dumps(json.loads(r.text), indent=2)

def delete(path) :
  r = requests.delete(api+path, headers=headers)
  if (r.text):
    print json.dumps(json.loads(r.text), indent=2)

generator = {
  "Amsterdam": {'mu':100, 'sigma':20},
  "Rotterdam": {'mu':20,  'sigma':20},
  "DenHaag":  {'mu':500,  'sigma':60},
  "Utrecht":   {'mu':40,  'sigma':20},
  "Eindhoven": {'mu':100, 'sigma':10},
  "Arnhem":    {'mu':200, 'sigma':50}
}

def randEvent() :
  account = random.randrange(1000)
  city = random.choice(generator.keys())
  salary = random.gauss(3000, 500)
  age = random.gauss(40, 5)
  amount = random.gauss(generator[city]['mu'],generator[city]['sigma'])
  event = {'account':'NL'+str(account), 'amount':amount, 'city':city, 'salary': salary, 'age':age}
  # event = {'account':'NL'+str(account), 'amount':amount, 'city':city}
  return event

while(1):
    post('/api/actors/1/in',randEvent() )
    time.sleep(0.01)