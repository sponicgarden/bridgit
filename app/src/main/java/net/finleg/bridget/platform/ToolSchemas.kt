package net.finleg.bridget.platform

import org.json.JSONArray
import org.json.JSONObject

/** Descriptions retain typed names and useful arguments across OpenAPI and MCP. */
object ToolSchemas {
    private fun fields(vararg names:String)=JSONObject().apply { names.forEach { spec -> val pieces=spec.split(':'); put(pieces[0],JSONObject().put("type",pieces.getOrElse(1) { "string" }).apply { if(pieces.getOrNull(1)=="array") put("items",JSONObject().put("type","string")) }) } }
    fun forTool(name:String):JSONObject {
        val p=when(name) {
            "events" -> fields("after:integer","wait:integer")
            "operation.get","interaction.get" -> fields("id","wait:integer")
            "interaction.create" -> fields("kind","title","text","choices:array","expires_seconds:integer")
            "interaction.cancel","session.stop","session.heartbeat","file.get","workflow.pause","workflow.run","stream.stop","stream.status","stream.frame" -> fields("id")
            "session.request" -> fields("apps:array","purpose","duration_seconds:integer")
            "screen.observe" -> fields("session_id","screenshot:boolean")
            "screen.wait" -> fields("session_id","expect:object","timeout_seconds:integer")
            "ui.action" -> fields("session_id","fence","observation_id","type","ref","text","direction","expect:object")
            "device.action" -> fields("type","url","package","text","language","recipient","subject","number","title","begin_ms:integer","end_ms:integer","hour:integer","minute:integer","seconds:integer","label","file_id","on:boolean","percent:integer","stream","mode","command","duration_seconds:integer","camera","max_width_px:integer")
            "notification.reply" -> fields("id","fingerprint","action_index:integer","text")
            "file.put" -> fields("name","mime","data_base64","note")
            "workflow.propose","workflow.validate" -> fields("name","trigger:object","steps:array","ui:object").apply { put("steps",JSONObject().put("type","array").put("items",JSONObject().put("type","object"))) }
            "stream.start" -> fields("camera","duration_seconds:integer","interval_seconds:integer")
            "images.ocr","images.barcode" -> fields("file_id","gallery_id","image_base64")
            "translation.run" -> fields("source","target","text")
            "translation.download" -> fields("source","target")
            "speech.transcribe" -> fields("language")
            "alert.trigger" -> fields("destinations:array","text")
            "alert.cancel" -> fields("id")
            "sensors.read" -> fields("which:array")
            "peripheral.action" -> fields("type","seconds:integer","address","service_uuid","characteristic_uuid","data_base64","device_id","interface:integer","endpoint:integer","max_bytes:integer")
            "note.create","feedback.create" -> fields("text")
            else -> JSONObject()
        }
        if(name in Commands.mutations) p.put("idempotency_key",JSONObject().put("type","string").put("description","Unique per logical request. Retries reuse the same key and exact input."))
        return JSONObject().put("type","object").put("properties",p).put("additionalProperties",false).put("required",JSONArray(if(name in Commands.mutations) listOf("idempotency_key") else emptyList<String>()))
    }
}
