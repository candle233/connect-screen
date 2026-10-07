import fs from 'node:fs';
const path = new URL('./reports.bin', import.meta.url);
const bytes = fs.readFileSync(path);
let offset=0, packets=0, malformed=0, wrongId=0, active=0, down=0, up=0, moves=0, countMismatches=0;
let prevDown=false, prevX=0, prevY=0, minX=16385, maxX=-1, minY=16385, maxY=-1;
let minCount=Infinity,maxCount=-Infinity, touchIds=new Set(), samples=[], anomalies=[], maxTipContacts=0, tipCountDistribution={};
while(offset<bytes.length) {
  const n=bytes[offset++];
  if(n!==64 || offset+n>bytes.length) { malformed++; break; }
  const r=bytes.subarray(offset,offset+n); offset+=n; packets++;
  if(r[0]!==4) { wrongId++; continue; }
  const count=r[55]; minCount=Math.min(minCount,count); maxCount=Math.max(maxCount,count);
  let point=null, extra=0;
  for(let i=0;i<10;i++) {
    const b=1+5*i, id=r[b]&63, tip=(r[b]>>6)&1;
    if(!tip) continue;
    extra++;
    const x=r[b+1]|(r[b+2]<<8), y=r[b+3]|(r[b+4]<<8);
    if(!point) point={id,x,y}; else { /* Track but never inject multitouch. */ }
    touchIds.add(id);
  }
  maxTipContacts=Math.max(maxTipContacts, extra);
  tipCountDistribution[extra]=(tipCountDistribution[extra]||0)+1;
  if(count!==extra) { countMismatches++; if(anomalies.length<12) anomalies.push({packet:packets,count,tipContacts:extra,contacts:Array.from({length:10},(_,i)=>{const b=1+5*i;return {id:r[b]&63,tip:(r[b]>>6)&1,x:r[b+1]|(r[b+2]<<8),y:r[b+3]|(r[b+4]<<8)}}).filter(x=>x.tip)}); }
  const pressed=point!==null;
  if(pressed) {
    active++; minX=Math.min(minX,point.x);maxX=Math.max(maxX,point.x);minY=Math.min(minY,point.y);maxY=Math.max(maxY,point.y);
    if(!prevDown) { down++; if(samples.length<10)samples.push({type:'D',...point}); }
    else { moves++; if((point.x!==prevX||point.y!==prevY)&&samples.length<10)samples.push({type:'M',...point}); }
    prevX=point.x;prevY=point.y;
  } else if(prevDown) { up++; if(samples.length<10)samples.push({type:'U',x:prevX,y:prevY}); }
  prevDown=pressed;
}
if(prevDown) up++;
console.log(JSON.stringify({file:path.pathname,bytes:bytes.length,packets,malformed,wrongId,activeReports:active,down,moveReports:moves,up,tipContactIds:[...touchIds].sort(),maxTipContacts,tipCountDistribution,contactCountRange:[minCount,maxCount],countMismatches,xRange:active?[minX,maxX]:null,yRange:active?[minY,maxY]:null,samples,anomalies},null,2));
