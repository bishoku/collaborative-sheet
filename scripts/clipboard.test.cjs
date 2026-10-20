const {test}=require('node:test');const assert=require('node:assert/strict');
const {parseClipboard,quote}=require('../src/main/resources/js/sheets.js');
test('Excel rectangular clipboard keeps empty cells',()=>assert.deepEqual(parseClipboard('A\tB\t\r\n1\t2\t\r\n'),[['A','B',''],['1','2','']]));
test('Excel quoted multiline and tabs remain one cell',()=>assert.deepEqual(parseClipboard('"line1\nline2"\t"has\ttab"\r\n"a""b"\tçığ'),[['line1\nline2','has\ttab'],['a"b','çığ']]));
test('copy text round trips quotes and multiline',()=>{const values=['Hello','line1\nline2','a"b','x\ty',''];assert.deepEqual(parseClipboard(values.map(quote).join('\t')),[values]);});
test('invalid Excel quotes fail before any paste',()=>assert.throws(()=>parseClipboard('"unfinished')));
