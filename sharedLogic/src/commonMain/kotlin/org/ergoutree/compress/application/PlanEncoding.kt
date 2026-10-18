package org.ergoutree.compress.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.ergoutree.compress.domain.ResolvedPlan

/** Historical plans retain the exact canonical field set used when they were frozen. */
object PlanEncoding {
    private val v1Keys = setOf("schemaVersion", "plannerVersion", "source", "media", "tools", "settings", "container", "destination")
    private val legacySettings = setOf("backend", "quality", "speed", "container")
    private val legacyTools = setOf("ffmpeg", "ffprobe", "checks", "buildConfiguration")
    private val newStreamKeys = setOf("bitrateBps", "sampleAspectRatio", "extradataHash")
    fun canonical(plan: ResolvedPlan): String {
        var json = SnapshotJson.parseToJsonElement(SnapshotJson.encodeToString(plan)).jsonObject
        if (plan.plannerVersion < 3) {
            val media = json.getValue("media").jsonObject
            json = JsonObject(json + mapOf(
                "settings" to JsonObject(json.getValue("settings").jsonObject.filterKeys { it in legacySettings }),
                "tools" to JsonObject(json.getValue("tools").jsonObject.filterKeys { it in legacyTools }),
                "media" to JsonObject(media + ("streams" to JsonArray(media.getValue("streams").jsonArray.map { stream -> JsonObject(stream.jsonObject.filterKeys { it !in newStreamKeys }) }))),
            ))
        }
        return if (plan.plannerVersion == 1) JsonObject(json.filterKeys { it in v1Keys }).toString() else json.toString()
    }
    fun canonicalList(plans: List<ResolvedPlan>) = JsonArray(plans.map { SnapshotJson.parseToJsonElement(canonical(it)) }).toString()
}
