import fs from 'node:fs';
const bytes = fs.readFileSync(new URL('./hid-report.bin', import.meta.url));
let page=0, usage=[], size=0, count=0, id=0, min=0, max=0, offsets={}, fields=[];
for (let p=0;p<bytes.length;) {
  const tag=bytes[p++]; if(tag===254) { p+=bytes[p]+2; continue; }
  const n=[0,1,2,4][tag&3], type=(tag>>2)&3, key=tag>>4;
  let value=0; for(let i=0;i<n;i++) value+=bytes[p++] * 2**(8*i);
  if(type===1) { if(key===0)page=value; if(key===1)min=value; if(key===2)max=value;
    if(key===7)size=value; if(key===8)id=value; if(key===9)count=value; }
  else if(type===2&&key===0) usage.push(value);
  else if(type===0) {
    if(key===8) { const offset=offsets[id]??0;
      fields.push({id,offset,size,count,page:'0x'+page.toString(16),usage:usage.map(x=>'0x'+x.toString(16)),flags:value,min,max});
      offsets[id]=offset+size*count;
    } usage=[];
  }
}
console.log(JSON.stringify({bits:offsets,fields},null,2));
