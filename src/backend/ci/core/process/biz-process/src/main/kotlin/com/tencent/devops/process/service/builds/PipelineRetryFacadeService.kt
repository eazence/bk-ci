/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.service.builds

import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.engine.common.VMUtils
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.engine.pojo.event.PipelineBuildContainerEvent
import com.tencent.devops.process.engine.service.PipelineContainerService
import com.tencent.devops.process.engine.service.PipelineTaskService
import com.tencent.devops.process.engine.service.detail.TaskBuildDetailService
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.ws.rs.core.Response

@Service
class PipelineRetryFacadeService @Autowired constructor(
    val dslContext: DSLContext,
    val pipelineEventDispatcher: PipelineEventDispatcher,
    val pipelineTaskService: PipelineTaskService,
    val pipelineContainerService: PipelineContainerService,
    val taskBuildDetailService: TaskBuildDetailService
) {
    fun runningBuildTaskRetry (
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        taskId: String? = null
    ): Boolean {
        logger.info("runningBuildTaskRetry $userId|$projectId|$pipelineId|$buildId|$taskId}")
        if (taskId.isNullOrEmpty()) {
            // 抛异常
            throw ParamBlankException("Invalid taskId")
        }
        val taskInfo = pipelineTaskService.getBuildTask(projectId, buildId, taskId)
        if (taskInfo == null) {
            logger.warn("runningBuild task retry task empty $projectId|$pipelineId|$buildId|$taskId")
            // 抛异常
            throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId)
            )
        }
        // 判断待重试task所属job是否为终态。 非终态判断是否是关机未完成。其他task直接报错
        // 此处请求可能早于关机到达。 若还未关机就点击重试，提示用户稍后再试
        val containerInfo = pipelineContainerService.getContainer(
            projectId = projectId,
            buildId = buildId,
            containerId = taskInfo.containerId,
            stageId = null
        )

        var jobFinish = true
        if (!containerInfo!!.status.isFinish()) {
            val runningTasks = pipelineTaskService.listContainerBuildTasks(
                projectId = projectId,
                buildId = buildId,
                containerSeqId = containerInfo.containerId,
                buildStatusSet = setOf(BuildStatus.RUNNING)
            )
            runningTasks.forEach {
                if (it.taskId.startsWith(VMUtils.getStopVmLabel()) || it.taskId.startsWith(VMUtils.getEndLabel())) {
                    jobFinish = false
                    return@forEach
                }
            }
        }

        if (!jobFinish) {
            logger.warn("retry runningJob: $projectId|$buildId stopVm not finish")
            throw ErrorCodeException(
                errorCode = ProcessMessageCode.ERROR_STOP_VM_UN_FINISH,
                defaultMessage = "$buildId｜${containerInfo.containerId} stopVm未完成,请稍后重试"
            )
        }

        val taskRecords = pipelineTaskService.listContainerBuildTasks(projectId, buildId, containerInfo.containerId)
        // 待重试task所属job对应的startVm，stopVm，endTask，对应task状态重置为Queue
        val startAndEndTask = mutableListOf<PipelineBuildTask>()
        taskRecords.forEach { task ->
            if (task.taskId == taskId) {
                startAndEndTask.add(task)
            } else if (task.taskName.startsWith(VMUtils.getCleanVmLabel()) &&
                task.taskId.startsWith(VMUtils.getStopVmLabel())) {
                startAndEndTask.add(task)
            } else if (task.taskName.startsWith(VMUtils.getPrepareVmLabel()) &&
                task.taskId.startsWith(VMUtils.getStartVmLabel())) {
                startAndEndTask.add(task)
            } else if (task.taskName.startsWith(VMUtils.getWaitLabel()) &&
                task.taskId.startsWith(VMUtils.getEndLabel())) {
                startAndEndTask.add(task)
            }
        }
        startAndEndTask.forEach {
            pipelineTaskService.updateTaskStatus(task = it, userId = userId, buildStatus = BuildStatus.QUEUE)
        }

        taskBuildDetailService.taskStart(projectId, buildId, taskId)

        // 修改容器状态位运行
        pipelineContainerService.updateContainerStatus(
            projectId = containerInfo.projectId,
            buildId = containerInfo.buildId,
            stageId = containerInfo.stageId,
            containerId = containerInfo.containerId,
            buildStatus = BuildStatus.QUEUE
        )

        // 发送container Refreash事件，重新开始task对应的调度
        pipelineEventDispatcher.dispatch(
            PipelineBuildContainerEvent(
                source = "runningBuildRetry$buildId|$taskId",
                containerId = taskInfo.containerId,
                containerHashId = taskInfo.containerHashId,
                stageId = taskInfo.stageId,
                pipelineId = taskInfo.pipelineId,
                buildId = taskInfo.buildId,
                userId = userId,
                projectId = taskInfo.projectId,
                actionType = ActionType.REFRESH,
                containerType = ""
            )
        )
        return true
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineRetryFacadeService::class.java)
    }
}