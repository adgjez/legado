package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiAgentJob
import io.legado.app.data.entities.AiAgentSession
import io.legado.app.data.entities.AiAgentTrace
import org.json.JSONObject
import java.util.UUID

object AiAgentStateStore {

    private const val DEFAULT_LEASE_MILLIS = 10 * 60 * 1000L

    data class Run(
        val sessionId: String,
        val jobId: String,
        val scope: String,
        val type: String
    )

    fun startRun(
        sessionId: String = UUID.randomUUID().toString(),
        scope: String,
        type: String,
        currentGoal: String = "",
        currentTask: String = "",
        inputJson: String = ""
    ): Run {
        markExpiredRunningJobs()
        val now = System.currentTimeMillis()
        val jobId = UUID.randomUUID().toString()
        appDb.aiAgentDao.upsertSession(
            AiAgentSession(
                sessionId = sessionId,
                scope = scope,
                status = AiAgentSession.STATUS_RUNNING,
                currentGoal = currentGoal.take(2_000),
                currentTask = currentTask.take(2_000),
                currentStep = "start",
                createdAt = appDb.aiAgentDao.session(sessionId)?.createdAt ?: now,
                updatedAt = now
            )
        )
        appDb.aiAgentDao.upsertJob(
            AiAgentJob(
                jobId = jobId,
                sessionId = sessionId,
                type = type,
                status = AiAgentJob.STATUS_RUNNING,
                inputJson = inputJson.take(16_000),
                leaseUntil = now + DEFAULT_LEASE_MILLIS,
                createdAt = now,
                updatedAt = now
            )
        )
        val run = Run(sessionId, jobId, scope, type)
        trace(run, AiAgentTrace.EVENT_STATUS, JSONObject().put("stage", "start"))
        return run
    }

    fun trace(
        run: Run?,
        eventType: String,
        payload: JSONObject,
        round: Int = 0,
        success: Boolean = true,
        usage: AiUsageStats? = null
    ) {
        if (run == null) return
        val now = System.currentTimeMillis()
        appDb.aiAgentDao.insertTrace(
            AiAgentTrace(
                sessionId = run.sessionId,
                jobId = run.jobId,
                round = round,
                eventType = eventType,
                payloadJson = payload.toString().take(16_000),
                usageJson = usage?.toJson()?.toString().orEmpty(),
                success = success,
                createdAt = now
            )
        )
        val checkpoint = JSONObject()
            .put("eventType", eventType)
            .put("round", round)
            .put("stage", payload.optString("stage"))
            .put("toolName", payload.optString("name"))
            .put("success", success)
            .put("updatedAt", now)
        appDb.aiAgentDao.updateJobCheckpoint(
            jobId = run.jobId,
            status = AiAgentJob.STATUS_RUNNING,
            checkpointJson = checkpoint.toString(),
            leaseUntil = now + DEFAULT_LEASE_MILLIS,
            updatedAt = now
        )
    }

    fun finish(run: Run?, success: Boolean, outputJson: String = "", error: String = "") {
        if (run == null) return
        val status = if (success) AiAgentJob.STATUS_DONE else AiAgentJob.STATUS_FAILED
        val sessionStatus = if (success) AiAgentSession.STATUS_DONE else AiAgentSession.STATUS_FAILED
        val now = System.currentTimeMillis()
        trace(
            run = run,
            eventType = if (success) AiAgentTrace.EVENT_STATUS else AiAgentTrace.EVENT_ERROR,
            payload = JSONObject()
                .put("stage", if (success) "finish" else "failed")
                .put("error", error.take(4_000)),
            success = success
        )
        appDb.aiAgentDao.finishJob(
            jobId = run.jobId,
            status = status,
            error = error.take(4_000),
            outputJson = outputJson.take(16_000),
            updatedAt = now
        )
        appDb.aiAgentDao.updateSessionStatus(
            sessionId = run.sessionId,
            status = sessionStatus,
            error = error.take(4_000),
            updatedAt = now
        )
    }

    fun cancel(run: Run?, reason: String = "") {
        if (run == null) return
        val now = System.currentTimeMillis()
        appDb.aiAgentDao.finishJob(
            jobId = run.jobId,
            status = AiAgentJob.STATUS_CANCELLED,
            error = reason.take(4_000),
            updatedAt = now
        )
        appDb.aiAgentDao.updateSessionStatus(
            sessionId = run.sessionId,
            status = AiAgentSession.STATUS_CANCELLED,
            error = reason.take(4_000),
            updatedAt = now
        )
    }

    fun activeJobs(): List<AiAgentJob> {
        markExpiredRunningJobs()
        return appDb.aiAgentDao.activeJobs()
    }

    fun markExpiredRunningJobs(now: Long = System.currentTimeMillis()) {
        appDb.aiAgentDao.expiredRunningJobs(now).forEach { job ->
            appDb.aiAgentDao.finishJob(
                jobId = job.jobId,
                status = AiAgentJob.STATUS_INTERRUPTED,
                error = "任务被系统中断",
                updatedAt = now
            )
            appDb.aiAgentDao.updateSessionStatus(
                sessionId = job.sessionId,
                status = AiAgentSession.STATUS_INTERRUPTED,
                error = "任务被系统中断",
                updatedAt = now
            )
        }
    }

    private fun AiUsageStats.toJson(): JSONObject {
        return JSONObject()
            .put("inputTokens", inputTokens)
            .put("cachedInputTokens", cachedInputTokens)
            .put("outputTokens", outputTokens)
            .put("totalTokens", totalTokens)
    }
}
