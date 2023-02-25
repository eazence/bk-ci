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
 *
 */

package com.tencent.devops.auth.service

import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.dao.AuthResourceDao
import com.tencent.devops.auth.dao.AuthResourceGroupDao
import com.tencent.devops.auth.pojo.AuthResourceInfo
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.util.PageUtil
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class AuthResourceService @Autowired constructor(
    private val dslContext: DSLContext,
    private val authResourceDao: AuthResourceDao,
    private val authResourceGroupDao: AuthResourceGroupDao
) {

    companion object {
        private val logger = LoggerFactory.getLogger(AuthResourceService::class.java)
    }

    @SuppressWarnings("LongParameterList")
    fun create(
        userId: String,
        projectCode: String,
        resourceType: String,
        resourceCode: String,
        resourceName: String,
        iamResourceCode: String,
        enable: Boolean,
        relationId: String
    ): Int {
        return authResourceDao.create(
            dslContext = dslContext,
            userId = userId,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode,
            resourceName = resourceName,
            iamResourceCode = iamResourceCode,
            enable = enable,
            relationId = relationId
        )
    }

    @SuppressWarnings("LongParameterList")
    fun update(
        projectCode: String,
        resourceType: String,
        resourceCode: String,
        resourceName: String
    ): Int {
        return authResourceDao.update(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode,
            resourceName = resourceName
        )
    }

    fun delete(
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ) {
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)
            authResourceDao.delete(
                dslContext = transactionContext,
                projectCode = projectCode,
                resourceType = resourceType,
                resourceCode = resourceCode
            )
            authResourceGroupDao.delete(
                dslContext = transactionContext,
                projectCode = projectCode,
                resourceType = resourceType,
                resourceCode = resourceCode
            )
        }
    }

    fun get(
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): AuthResourceInfo {
        val record = authResourceDao.get(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        ) ?: throw ErrorCodeException(
            errorCode = AuthMessageCode.RESOURCE_NOT_FOUND,
            params = arrayOf(resourceCode),
            defaultMessage = "the resource not exists, resourceCode:$resourceCode"
        )
        return authResourceDao.convert(record)
    }

    fun getOrNull(
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): AuthResourceInfo? {
        val record = authResourceDao.get(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        ) ?: return null
        return authResourceDao.convert(record)
    }

    fun enable(
        userId: String,
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        return authResourceDao.enable(
            dslContext = dslContext,
            userId = userId,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
    }

    fun disable(
        userId: String,
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        return authResourceDao.disable(
            dslContext = dslContext,
            userId = userId,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
    }

    @SuppressWarnings("LongParameterList")
    fun list(
        projectCode: String,
        resourceType: String,
        resourceName: String?,
        page: Int,
        pageSize: Int
    ): List<AuthResourceInfo> {
        val sqlLimit = PageUtil.convertPageSizeToSQLLimit(page, pageSize)
        val resourceList: MutableList<AuthResourceInfo> = ArrayList()
        authResourceDao.list(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceName = resourceName,
            resourceType = resourceType,
            limit = sqlLimit.limit,
            offset = sqlLimit.offset
        ).map { resourceList.add(authResourceDao.convert(it)) }
        return resourceList
    }

    fun listByProjectAndType(
        projectCode: String,
        resourceType: String
    ): List<String> {
        return authResourceDao.getResourceCodeByType(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType
        )
    }

    fun listByIamCodes(
        resourceType: String,
        iamResourceCodes: List<String>
    ): List<AuthResourceInfo> {
        return authResourceDao.listByByIamCodes(
            dslContext = dslContext,
            resourceType = resourceType,
            iamResourceCodes = iamResourceCodes
        ).map { authResourceDao.convert(it) }
    }
}
