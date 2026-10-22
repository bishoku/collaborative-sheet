const {test}=require('node:test');const assert=require('node:assert/strict');
const {parseClipboard,quote,inferCellData,shiftFormula,formatDisplayValue}=require('../src/main/resources/js/sheets.js');
test('Excel rectangular clipboard keeps empty cells',()=>assert.deepEqual(parseClipboard('A\tB\t\r\n1\t2\t\r\n'),[['A','B',''],['1','2','']]));
test('Excel quoted multiline and tabs remain one cell',()=>assert.deepEqual(parseClipboard('"line1\nline2"\t"has\ttab"\r\n"a""b"\tçığ'),[['line1\nline2','has\ttab'],['a"b','çığ']]));
test('copy text round trips quotes and multiline',()=>{const values=['Hello','line1\nline2','a"b','x\ty',''];assert.deepEqual(parseClipboard(values.map(quote).join('\t')),[values]);});
test('invalid Excel quotes fail before any paste',()=>assert.throws(()=>parseClipboard('"unfinished')));
test('inferCellData detects numbers, booleans, formulas and text',()=>{
  assert.deepEqual(inferCellData('1250.5',{bold:true}),{bold:true,value:'1250.5',type:'number',formula:''});
  assert.deepEqual(inferCellData('TRUE',{}),{value:'true',type:'boolean',formula:''});
  assert.deepEqual(inferCellData('=SUM(A1:A5)',{}),{value:'=SUM(A1:A5)',formula:'=SUM(A1:A5)',type:'text'});
  assert.deepEqual(inferCellData('Hello\nWorld',{}),{value:'Hello\nWorld',type:'text',wrap:true,formula:''});
});
test('relative fill preserves quoted and absolute references',()=>{
 assert.equal(shiftFormula('=IF(A1>5,$B$1,"A1")',1,1),'=IF(B2>5,$B$1,"A1")');
 assert.equal(shiftFormula('=A1',-1,0),'=#REF!');
});
test('text identifiers and long numbers retain exact digits',()=>{
 assert.equal(inferCellData('9007199254740993',{format:'@'}).value,'9007199254740993');
 assert.equal(inferCellData('9007199254740993',{}).type,'text');
 assert.equal(inferCellData('00123',{}).value,'00123');
 assert.equal(inferCellData('plain',{formula:'=A1'}).formula,'');
 assert.equal(formatDisplayValue('178500','#,##0','number'),'178,500');
});

test('numeric-looking text is never rounded by display formatting', () => {
  const {formatDisplayValue}=require('../src/main/resources/js/sheets.js');
  assert.equal(formatDisplayValue('9007199254740993','#,##0','text'),'9007199254740993');
});
