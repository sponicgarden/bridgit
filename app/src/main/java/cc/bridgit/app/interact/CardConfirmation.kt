package cc.bridgit.app.interact

import org.json.JSONObject

/** One event after durable acceptance. Cards can draw their own in-document confirmation. */
object CardConfirmation {
    const val EVENT = "bridgit:answered"
    const val CLOSE_DELAY_MS = 5000L
    fun script(answer: JSONObject?, title: String, message: String, closeAfterMs: Long? = null): String {
        val payload = JSONObject().put("answer", answer ?: JSONObject.NULL).put("close_after_ms", closeAfterMs ?: JSONObject.NULL)
        return """(function(){
var event=new CustomEvent('${EVENT}',{cancelable:true,detail:${payload}});
if(!document.dispatchEvent(event))return;
var body=document.body,style=getComputedStyle(body),panel=document.createElement('section');
panel.setAttribute('role','status');panel.setAttribute('aria-live','polite');
panel.style.cssText='position:fixed;inset:0;z-index:2147483647;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:28px;';
panel.style.background=style.backgroundColor==='rgba(0, 0, 0, 0)'?'#f5f2eb':style.backgroundColor;
panel.style.color=style.color;panel.style.fontFamily=style.fontFamily;
var icon=document.createElement('div');icon.textContent='✓';icon.style.cssText='font-size:44px;margin-bottom:16px;';
var heading=document.createElement('h2');heading.textContent=${JSONObject.quote(title)};
var note=document.createElement('p');note.textContent=${JSONObject.quote(message)};
panel.append(icon,heading,note);body.appendChild(panel);
document.querySelectorAll('button,input,select,textarea').forEach(function(e){e.disabled=true;});
})();"""
    }
}
