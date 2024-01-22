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

package com.tencent.devops.scm.pojo

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.annotations.ApiModel
import io.swagger.annotations.ApiModelProperty

@ApiModel("SVN仓库文件树信息")
data class SvnTreeInfo(
    val count: Int,
    val files: List<SvnTreeNodeInfo>
)

@ApiModel("SVN仓库文件树节点信息")
data class SvnTreeNodeInfo(
    val file: SvnFile,
    @JsonProperty("file_lock")
    val fileLock: Boolean? = false
)

@ApiModel("SVN仓库文件信息")
data class SvnFile(
    @ApiModelProperty("文件名")
    val name: String,
    @ApiModelProperty("文件全路径")
    val path: String,
    @ApiModelProperty("文件大小")
    val size: Long,
    @ApiModelProperty("文件版本号")
    val revision: Long,
    @ApiModelProperty("作者")
    val author: String,
    @ApiModelProperty("提交信息")
    val commitMessage: String?,
    @ApiModelProperty("是否为目录")
    val directory: Boolean,
    @ApiModelProperty("是否为外链")
    val submodule: Boolean,
    @ApiModelProperty("是否为文件")
    val file: Boolean
)
