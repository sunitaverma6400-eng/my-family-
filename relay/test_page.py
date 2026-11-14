"""Single-file browser test page for the relay (no app needed)."""
PAGE = r"""<!doctype html><html lang="hi"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Stark Relay Test</title>
<style>
body{font-family:sans-serif;background:#0b1220;color:#e6f1ff;margin:0;padding:14px;max-width:640px}
input,select,textarea,button{font-size:16px;width:100%;box-sizing:border-box;margin:5px 0;padding:10px;border-radius:8px;border:1px solid #2b3a55;background:#121c30;color:#e6f1ff}
button{background:#1e6fd9;border:0}button.alt{background:#2b3a55}
pre{white-space:pre-wrap;word-break:break-word;background:#0f1729;padding:10px;border-radius:8px;border:1px solid #2b3a55;font-size:14px}
</style></head><body>
<h3>Stark Family — Relay Test</h3>
<input id="tok" type="password" placeholder="APP_TOKEN (एक बार डालिए)">
<select id="ch"><option value="jarvis">Jarvis</option><option value="vision">Vision</option></select>
<textarea id="msg" rows="3">नमस्ते, कैसे हो?</textarea>
<button onclick="chat()">भेजो</button>
<button class="alt" onclick="get('/brain_status')">Brain Status</button>
<button class="alt" onclick="get('/diag')">मॉडल जाँच</button>
<pre id="out">यहाँ नतीजा दिखेगा।</pre>
<script>
const $=id=>document.getElementById(id);
$('tok').value=localStorage.getItem('tok')||'';
async function loadChars(){try{const r=await fetch('/characters',{headers:hdr()});if(!r.ok)return;const l=await r.json();const cur=$('ch').value;$('ch').innerHTML=l.map(c=>'<option value="'+c.id+'">'+c.name+'</option>').join('');if(l.some(c=>c.id===cur))$('ch').value=cur}catch(e){}}
$('tok').addEventListener('change',loadChars);if($('tok').value)loadChars();
function hdr(){localStorage.setItem('tok',$('tok').value.trim());return {'X-App-Token':$('tok').value.trim(),'Content-Type':'application/json'}}
async function show(r){let t=await r.text();try{t=JSON.stringify(JSON.parse(t),null,2)}catch(e){}$('out').textContent='HTTP '+r.status+'\n'+t}
async function get(p){$('out').textContent='…';try{await show(await fetch(p,{headers:hdr()}))}catch(e){$('out').textContent='Network error: '+e}}
async function chat(){$('out').textContent='… (Render सो रहा हो तो 30-60 सेकंड लग सकते हैं)';
try{await show(await fetch('/chat',{method:'POST',headers:hdr(),body:JSON.stringify({character:$('ch').value,message:$('msg').value})}))}catch(e){$('out').textContent='Network error: '+e}}
</script></body></html>"""
