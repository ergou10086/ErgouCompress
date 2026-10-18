package org.ergoutree.compress.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.ergoutree.compress.domain.ResolvedPlan

/** Historical plans retain the exact canonical field set used when they were frozen. */
object PlanEncoding {
    private val v1Keys = setOf("schemaVersion", "plannerVersion", "source", "media", "tools", "settings", "container", "destination")
    fun canonical(plan: ResolvedPlan): String {
        val json = SnapshotJson.parseToJsonElement(SnapshotJson.encodeToString(plan)).jsonObject
        return if (plan.plannerVersion == 1) JsonObject(json.filterKeys { it in v1Keys }).toString() else json.toString()
    }
    fun canonicalList(plans: List<ResolvedPlan>) = JsonArray(plans.map { SnapshotJson.parseToJsonElement(canonical(it)) }).toString()
}
