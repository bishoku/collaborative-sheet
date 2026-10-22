const {test}=require('node:test');const assert=require('node:assert/strict');
const {WriteQueue}=require('../src/main/resources/js/sheets.js');
const tick=()=>new Promise(r=>setImmediate(r));
const initial=()=>({revision:1,rows:['r'],columns:['c'],cells:{}});
const op=(value,version=0)=>({type:'patch',edits:[{row:'r',column:'c',expectedVersion:version,cell:{value,type:'text'}}]});
test('100 rapid edits are sent FIFO and versions converge',async()=>{
 let seq=1,calls=[];const q=new WriteQueue(async item=>{await tick();calls.push(item);return {changes:[{revision:++seq,cells:{'r:c':{...item.op.edits[0].cell,version:seq-1}}}]};},()=>{},()=>String(Math.random()));q.confirmed=initial();
 for(let i=0;i<100;i++)q.enqueue(op(String(i),i));
 assert.equal(q.projected().cells['r:c'].value,'99');
 while(q.running)await tick();assert.equal(calls.length,100);assert.deepEqual(calls.map(c=>c.op.edits[0].expectedVersion),Array.from({length:100},(_,i)=>i));assert.equal(q.confirmed.cells['r:c'].value,'99');assert.equal(q.items.length,0);
});
for(const status of [400,403,409,503,0])test('failure '+status+' retains the entire queue and immutable retry receipt',async()=>{
 let fail=true,calls=[];const q=new WriteQueue(async item=>{calls.push({...item});if(fail)throw Object.assign(new Error('failure'),{status});return {document:{...initial(),revision:calls.length,cells:{'r:c':{...item.op.edits[0].cell,version:item.op.edits[0].expectedVersion+1}}}};},()=>{},()=>String(Math.random()));q.confirmed=initial();q.enqueue(op('a'));q.enqueue(op('b',1));await tick();assert.equal(q.items.length,2);const saved=JSON.stringify(q.items);fail=false;q.retry();await tick();assert.equal(calls[0].id,calls[1].id);assert.equal(calls[0].body,calls[1].body);assert.equal(q.items.length,0);assert.ok(saved.includes('b'));assert.equal(q.confirmed.cells['r:c'].value,'b');
});
test('ack applies server values, formula results and styles before advancing revision',async()=>{
 const q=new WriteQueue(async()=>({changes:[{revision:2,cells:{'r:c':{value:'x',bold:true,version:1},'r:d':{value:'30',formula:'=A1*2',version:1}}}]}),()=>{});q.confirmed=initial();q.enqueue(op('x'));await tick();assert.equal(q.confirmed.cells['r:c'].bold,true);assert.equal(q.confirmed.cells['r:d'].value,'30');assert.equal(q.confirmed.revision,2);
});
test('discard restores confirmed state after an invalid optimistic edit',async()=>{
 const q=new WriteQueue(async()=>{throw new Error('invalid')},()=>{});q.confirmed=initial();q.enqueue(op('unsaved'));await tick();assert.equal(q.projected().cells['r:c'].value,'unsaved');q.discard();assert.deepEqual(q.projected().cells,{});
});
