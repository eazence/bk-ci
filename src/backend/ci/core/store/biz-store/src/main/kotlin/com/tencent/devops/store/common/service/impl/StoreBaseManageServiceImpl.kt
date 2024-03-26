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

package com.tencent.devops.store.common.service.impl

import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.constant.INIT_VERSION
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.ReflectUtil
import com.tencent.devops.common.api.util.UUIDUtil
import com.tencent.devops.common.service.utils.SpringContextUtil
import com.tencent.devops.model.store.tables.records.TStoreBaseRecord
import com.tencent.devops.store.common.dao.ClassifyDao
import com.tencent.devops.store.common.dao.StoreBaseEnvExtManageDao
import com.tencent.devops.store.common.dao.StoreBaseEnvManageDao
import com.tencent.devops.store.common.dao.StoreBaseExtManageDao
import com.tencent.devops.store.common.dao.StoreBaseFeatureExtManageDao
import com.tencent.devops.store.common.dao.StoreBaseFeatureManageDao
import com.tencent.devops.store.common.dao.StoreBaseManageDao
import com.tencent.devops.store.common.dao.StoreBaseQueryDao
import com.tencent.devops.store.common.dao.StoreVersionLogDao
import com.tencent.devops.store.common.service.StoreBaseManageService
import com.tencent.devops.store.common.service.StoreCommonService
import com.tencent.devops.store.common.service.StoreSpecBusService
import com.tencent.devops.store.common.service.StoreWebsocketService
import com.tencent.devops.store.common.utils.VersionUtils
import com.tencent.devops.store.constant.StoreMessageCode
import com.tencent.devops.store.pojo.common.enums.ReleaseTypeEnum
import com.tencent.devops.store.pojo.common.enums.StoreStatusEnum
import com.tencent.devops.store.pojo.common.enums.StoreTypeEnum
import com.tencent.devops.store.pojo.common.publication.StoreBaseDataPO
import com.tencent.devops.store.pojo.common.publication.StoreBaseEnvDataPO
import com.tencent.devops.store.pojo.common.publication.StoreBaseEnvExtDataPO
import com.tencent.devops.store.pojo.common.publication.StoreBaseExtDataPO
import com.tencent.devops.store.pojo.common.publication.StoreBaseFeatureDataPO
import com.tencent.devops.store.pojo.common.publication.StoreBaseFeatureExtDataPO
import com.tencent.devops.store.pojo.common.publication.StoreUpdateRequest
import com.tencent.devops.store.pojo.common.version.VersionModel
import org.apache.commons.codec.digest.DigestUtils
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
@Suppress("LongParameterList")
class StoreBaseManageServiceImpl @Autowired constructor(
    private val dslContext: DSLContext,
    private val classifyDao: ClassifyDao,
    private val storeBaseQueryDao: StoreBaseQueryDao,
    private val storeBaseManageDao: StoreBaseManageDao,
    private val storeBaseExtManageDao: StoreBaseExtManageDao,
    private val storeBaseFeatureManageDao: StoreBaseFeatureManageDao,
    private val storeBaseFeatureExtManageDao: StoreBaseFeatureExtManageDao,
    private val storeBaseEnvManageDao: StoreBaseEnvManageDao,
    private val storeBaseEnvExtManageDao: StoreBaseEnvExtManageDao,
    private val storeVersionLogDao: StoreVersionLogDao,
    private val storeCommonService: StoreCommonService,
    private val storeWebsocketService: StoreWebsocketService
) : StoreBaseManageService {

    private val logger = LoggerFactory.getLogger(StoreBaseManageServiceImpl::class.java)

    override fun doStoreI18nConversion(storeUpdateRequest: StoreUpdateRequest) {
        val storeBaseUpdateRequest = storeUpdateRequest.baseInfo
        val storeType = storeBaseUpdateRequest.storeType
        getStoreSpecBusService(storeType).doStoreI18nConversionSpecBus(storeUpdateRequest)
    }

    override fun checkStoreUpdateParam(
        storeUpdateRequest: StoreUpdateRequest
    ) {
        val storeBaseUpdateRequest = storeUpdateRequest.baseInfo
        val storeType = storeBaseUpdateRequest.storeType
        val storeCode = storeBaseUpdateRequest.storeCode
        val name = storeBaseUpdateRequest.name
        val versionInfo = storeBaseUpdateRequest.versionInfo
        val classifyCode = storeBaseUpdateRequest.classifyCode
        // 校验分类信息是否准确
        val classifyRecord =
            classifyDao.getClassifyByCode(dslContext, classifyCode, storeType) ?: throw ErrorCodeException(
                errorCode = CommonMessageCode.PARAMETER_IS_INVALID,
                params = arrayOf(classifyCode)
            )
        var extBaseInfo = storeBaseUpdateRequest.extBaseInfo
        if (extBaseInfo == null) {
            extBaseInfo = mutableMapOf()
        }
        extBaseInfo[classifyCode] = classifyRecord.id
        // 判断名称是否重复（升级允许名称一样）
        validateStoreName(storeType = storeType, storeCode = storeCode, name = name)
        // 校验前端传的版本号是否正确
        validateStoreVersion(storeCode = storeCode, storeType = storeType, versionInfo = versionInfo, name = name)
        // 处理检查组件升级参数个性化逻辑
        getStoreSpecBusService(storeType).doCheckStoreUpdateParamSpecBus(storeUpdateRequest)
    }

    override fun doStoreUpdateDataPersistent(userId: String, storeUpdateRequest: StoreUpdateRequest) {
        val storeBaseUpdateRequest = storeUpdateRequest.baseInfo
        val storeType = storeBaseUpdateRequest.storeType
        val storeCode = storeBaseUpdateRequest.storeCode
        val name = storeBaseUpdateRequest.name
        val versionInfo = storeBaseUpdateRequest.versionInfo
        val version = versionInfo.version
        val releaseType = versionInfo.releaseType
        val newestBaseRecord =
            storeBaseQueryDao.getNewestComponentByCode(dslContext, storeCode, storeType) ?: throw ErrorCodeException(
                errorCode = CommonMessageCode.PARAMETER_IS_INVALID,
                params = arrayOf(storeCode)
            )
        val newVersionFlag = !(releaseType == ReleaseTypeEnum.NEW || releaseType == ReleaseTypeEnum.CANCEL_RE_RELEASE)
        val storeId = if (newVersionFlag) {
            DigestUtils.md5Hex("$storeType-$storeCode-$version")
        } else {
            newestBaseRecord.id
        }
        val baseRecord = storeBaseQueryDao.getComponent(
            dslContext = dslContext,
            storeCode = storeCode,
            version = version,
            storeType = storeType
        )!!
        val latestFlag = if (newVersionFlag) {
            false
        } else {
            baseRecord.latestFlag
        }
        val status = getStoreSpecBusService(storeType).getStoreUpdateStatus()
        val extBaseInfo = storeBaseUpdateRequest.extBaseInfo
        val storeBaseDataPO = StoreBaseDataPO(
            id = storeId,
            storeCode = storeCode,
            storeType = storeType,
            name = name,
            version = version,
            status = status,
            logoUrl = storeBaseUpdateRequest.logoUrl,
            latestFlag = latestFlag,
            publisher = versionInfo.publisher,
            classifyId = extBaseInfo?.get(storeBaseUpdateRequest.classifyCode)?.toString() ?: "",
            creator = userId,
            modifier = userId
        )
        extBaseInfo?.remove(storeBaseUpdateRequest.classifyCode)
        var storeBaseExtDataPOs: MutableList<StoreBaseExtDataPO>? = null
        extBaseInfo?.forEach { (key, value) ->
            if (storeBaseExtDataPOs == null) {
                storeBaseExtDataPOs = mutableListOf()
            }
            storeBaseExtDataPOs?.add(
                StoreBaseExtDataPO(
                    id = UUIDUtil.generate(),
                    storeId = storeId,
                    storeCode = storeCode,
                    storeType = storeType,
                    fieldName = key,
                    fieldValue = JsonUtil.toJson(value, false),
                    creator = userId,
                    modifier = userId
                )
            )
        }
        val baseFeatureInfo = storeBaseUpdateRequest.baseFeatureInfo
        var storeBaseFeatureDataPO: StoreBaseFeatureDataPO? = null
        var storeBaseFeatureExtDataPOs: MutableList<StoreBaseFeatureExtDataPO>? = null
        baseFeatureInfo?.let {
            val featureId = UUIDUtil.generate()
            storeBaseFeatureDataPO = StoreBaseFeatureDataPO(
                id = featureId,
                storeCode = storeCode,
                storeType = storeType,
                type = baseFeatureInfo.type,
                rdType = baseFeatureInfo.rdType?.name,
                creator = userId,
                modifier = userId
            )
            val extBaseFeatureInfo = baseFeatureInfo.extBaseFeatureInfo
            extBaseFeatureInfo?.forEach { (key, value) ->
                if (storeBaseFeatureExtDataPOs == null) {
                    storeBaseFeatureExtDataPOs = mutableListOf()
                }
                storeBaseFeatureExtDataPOs?.add(
                    StoreBaseFeatureExtDataPO(
                        id = UUIDUtil.generate(),
                        featureId = featureId,
                        storeCode = storeCode,
                        storeType = storeType,
                        fieldName = key,
                        fieldValue = JsonUtil.toJson(value, false),
                        creator = userId,
                        modifier = userId
                    )
                )
            }
        }

        val baseEnvInfos = storeBaseUpdateRequest.baseEnvInfos
        var storeBaseEnvDataPOs: MutableList<StoreBaseEnvDataPO>? = null
        var storeBaseEnvExtDataPOs: MutableList<StoreBaseEnvExtDataPO>? = null
        baseEnvInfos?.forEach { baseEnvInfo ->
            val envId = UUIDUtil.generate()
            val storeBaseEnvDataPO = StoreBaseEnvDataPO(
                id = envId,
                storeId = storeId,
                language = baseEnvInfo.language,
                creator = userId,
                modifier = userId
            )
            if (storeBaseEnvDataPOs == null) {
                storeBaseEnvDataPOs = mutableListOf()
            }
            storeBaseEnvDataPOs?.add(storeBaseEnvDataPO)
            val extBaseFeatureInfo = baseEnvInfo.extBaseFeatureInfo
            extBaseFeatureInfo?.forEach { (key, value) ->
                if (storeBaseEnvExtDataPOs == null) {
                    storeBaseEnvExtDataPOs = mutableListOf()
                }
                storeBaseEnvExtDataPOs?.add(
                    StoreBaseEnvExtDataPO(
                        id = UUIDUtil.generate(),
                        envId = envId,
                        storeId = storeId,
                        fieldName = key,
                        fieldValue = JsonUtil.toJson(value, false),
                        creator = userId,
                        modifier = userId
                    )
                )
            }
        }
        dslContext.transaction { t ->
            val context = DSL.using(t)
            storeBaseManageDao.saveStoreBaseData(context, storeBaseDataPO)
            if (!storeBaseExtDataPOs.isNullOrEmpty()) {
                storeBaseExtManageDao.batchSave(context, storeBaseExtDataPOs!!)
            }
            storeBaseFeatureDataPO?.let {
                storeBaseFeatureManageDao.saveStoreBaseFeatureData(context, it)
            }
            if (!storeBaseFeatureExtDataPOs.isNullOrEmpty()) {
                storeBaseFeatureExtManageDao.batchSave(context, storeBaseFeatureExtDataPOs!!)
            }
            if (!storeBaseEnvDataPOs.isNullOrEmpty()) {
                storeBaseEnvManageDao.batchSave(context, storeBaseEnvDataPOs!!)
            }
            if (!storeBaseEnvExtDataPOs.isNullOrEmpty()) {
                storeBaseEnvExtManageDao.batchSave(context, storeBaseEnvExtDataPOs!!)
            }
            storeVersionLogDao.saveStoreVersion(
                dslContext = context,
                userId = userId,
                storeId = storeId,
                releaseType = releaseType,
                versionContent = versionInfo.versionContent
            )
        }
    }

    private fun getStoreSpecBusService(storeType: StoreTypeEnum): StoreSpecBusService {
        return SpringContextUtil.getBean(StoreSpecBusService::class.java, getSpecBusServiceBeanName(storeType))
    }

    private fun getSpecBusServiceBeanName(storeType: StoreTypeEnum): String {
        return "${storeType}_SPEC_BUS_SERVICE"
    }

    private fun validateStoreVersion(
        storeCode: String,
        storeType: StoreTypeEnum,
        versionInfo: VersionModel,
        name: String
    ) {
        val releaseType = versionInfo.releaseType
        val opBaseRecord = generateOpBaseRecord(storeCode, storeType, releaseType)
        val version = versionInfo.version
        val dbVersion = opBaseRecord.version
        val dbStatus = opBaseRecord.status
        // 判断首个版本对应的请求是否合法
        if (releaseType == ReleaseTypeEnum.NEW && dbVersion == INIT_VERSION &&
            dbStatus != StoreStatusEnum.INIT.name
        ) {
            throw ErrorCodeException(errorCode = CommonMessageCode.ERROR_REST_EXCEPTION_COMMON_TIP)
        }
        // 最近的版本处于上架中止状态，重新升级版本号不变
        val cancelFlag = dbStatus == StoreStatusEnum.GROUNDING_SUSPENSION.name
        val requireVersionList =
            if (cancelFlag && releaseType == ReleaseTypeEnum.CANCEL_RE_RELEASE) {
                listOf(dbVersion)
            } else {
                // 历史大版本下的小版本更新模式需获取要更新大版本下的最新版本
                val reqVersion = if (releaseType == ReleaseTypeEnum.HIS_VERSION_UPGRADE) {
                    storeBaseQueryDao.getComponent(
                        dslContext = dslContext,
                        storeCode = storeCode,
                        version = VersionUtils.convertLatestVersion(version),
                        storeType = storeType
                    )?.version
                } else {
                    null
                }
                storeCommonService.getRequireVersion(
                    reqVersion = reqVersion,
                    dbVersion = dbVersion,
                    releaseType = releaseType
                )
            }
        if (!requireVersionList.contains(version)) {
            throw ErrorCodeException(
                errorCode = StoreMessageCode.USER_IMAGE_VERSION_IS_INVALID,
                params = arrayOf(version, requireVersionList.toString())
            )
        }
        // 判断最近一个版本的状态，如果不是首次发布，则只有处于终态的组件状态才允许添加新的版本
        checkAddVersionCondition(dbVersion = dbVersion, releaseType = releaseType, dbStatus = dbStatus, name = name)
    }

    private fun generateOpBaseRecord(
        storeCode: String,
        storeType: StoreTypeEnum,
        releaseType: ReleaseTypeEnum
    ): TStoreBaseRecord {
        val maxVersionBaseRecord = storeBaseQueryDao.getMaxVersionComponentByCode(dslContext, storeCode, storeType)
            ?: throw ErrorCodeException(
                errorCode = CommonMessageCode.PARAMETER_IS_INVALID,
                params = arrayOf(storeCode)
            )
        val newestBaseRecord = storeBaseQueryDao.getNewestComponentByCode(dslContext, storeCode, storeType)!!
        val opBaseRecord = if (releaseType == ReleaseTypeEnum.CANCEL_RE_RELEASE) {
            newestBaseRecord
        } else {
            maxVersionBaseRecord
        }
        return opBaseRecord
    }

    private fun checkAddVersionCondition(
        dbVersion: String,
        releaseType: ReleaseTypeEnum,
        dbStatus: String,
        name: String
    ) {
        if (dbVersion.isNotBlank() && releaseType != ReleaseTypeEnum.NEW) {
            val storeFinalStatusList = mutableListOf(
                StoreStatusEnum.AUDIT_REJECT.name,
                StoreStatusEnum.RELEASED.name,
                StoreStatusEnum.GROUNDING_SUSPENSION.name,
                StoreStatusEnum.UNDERCARRIAGED.name
            )
            if (!storeFinalStatusList.contains(dbStatus)) {
                throw ErrorCodeException(
                    errorCode = StoreMessageCode.USER_IMAGE_VERSION_IS_NOT_FINISH,
                    params = arrayOf(name, dbVersion)
                )
            }
        }
    }

    private fun validateStoreName(
        storeType: StoreTypeEnum,
        storeCode: String,
        name: String
    ) {
        var flag = false
        val count = storeBaseQueryDao.countByCondition(dslContext = dslContext, storeType = storeType, name = name)
        if (count > 0) {
            flag = storeBaseQueryDao.countByCondition(
                dslContext = dslContext,
                storeType = storeType,
                name = name,
                storeCode = storeCode
            ) < count
        }
        if (flag) {
            throw ErrorCodeException(
                errorCode = CommonMessageCode.PARAMETER_IS_INVALID,
                params = arrayOf(name)
            )
        }
    }
}
