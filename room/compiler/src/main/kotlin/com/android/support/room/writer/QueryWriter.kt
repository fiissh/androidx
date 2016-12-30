/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.support.room.writer

import com.android.support.room.ext.L
import com.android.support.room.ext.RoomTypeNames.STRING_UTIL
import com.android.support.room.ext.S
import com.android.support.room.ext.T
import com.android.support.room.ext.arrayTypeName
import com.android.support.room.ext.typeName
import com.android.support.room.parser.QueryType
import com.android.support.room.parser.SectionType.BIND_VAR
import com.android.support.room.parser.SectionType.NEWLINE
import com.android.support.room.parser.SectionType.TEXT
import com.android.support.room.solver.CodeGenScope
import com.android.support.room.vo.QueryMethod
import com.android.support.room.vo.QueryParameter
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeName

/**
 * Writes the SQL query and arguments for a QueryMethod.
 */
class QueryWriter(val queryMethod: QueryMethod) {
    fun prepareReadAndBind(outSqlQueryName: String, outArgsName: String, scope: CodeGenScope) {
        val listSizeVars = createSqlQueryAndArgs(outSqlQueryName, outArgsName, scope)
        bindArgs(outArgsName, listSizeVars, scope)
    }

    fun prepareQuery(outSqlQueryName: String, scope: CodeGenScope) {
        createSqlQueryAndArgs(outSqlQueryName, null, scope)
    }

    private fun createSqlQueryAndArgs(outSqlQueryName: String, outArgsName: String?,
                                      scope: CodeGenScope): List<Pair<QueryParameter, String>> {
        val listSizeVars = arrayListOf<Pair<QueryParameter, String>>()
        val varargParams = queryMethod.parameters
                .filter { it.queryParamAdapter?.isMultiple ?: false }
        val sectionToParamMapping = queryMethod.sectionToParamMapping
        val knownQueryArgsCount = sectionToParamMapping.filterNot {
            it.second?.queryParamAdapter?.isMultiple ?: false
        }.size
        scope.builder().apply {
            if (varargParams.isNotEmpty()) {
                val stringBuilderVar = scope.getTmpVar("_stringBuilder")
                addStatement("$T $L = $T.newStringBuilder()",
                        ClassName.get(StringBuilder::class.java), stringBuilderVar, STRING_UTIL)
                queryMethod.query.sections.forEach {
                    when (it.type) {
                        TEXT -> addStatement("$L.append($S)", stringBuilderVar, it.text)
                        NEWLINE -> addStatement("$L.append($S)", "\n")
                        BIND_VAR -> {
                            // If it is null, will be reported as error before. We just try out
                            // best to generate as much code as possible.
                            sectionToParamMapping.firstOrNull { mapping ->
                                mapping.first == it
                            }?.let { pair ->
                                if (pair.second?.queryParamAdapter?.isMultiple ?: false) {
                                    val tmpCount = scope.getTmpVar("_inputSize")
                                    listSizeVars.add(Pair(pair.second!!, tmpCount))
                                    pair.second
                                            ?.queryParamAdapter
                                            ?.getArgCount(pair.second!!.name, tmpCount, scope)
                                    addStatement("$T.appendPlaceholders($L, $L)",
                                            STRING_UTIL, stringBuilderVar, tmpCount)
                                } else {
                                    addStatement("$L.append($S)", stringBuilderVar, "?")
                                }
                            }
                        }
                    }
                }

                addStatement("$T $L = $L.toString()", String::class.typeName(),
                        outSqlQueryName, stringBuilderVar)
                if (outArgsName != null) {
                    val argCount = scope.getTmpVar("_argCount")
                    addStatement("final $T $L = $L$L", TypeName.INT, argCount, knownQueryArgsCount,
                            listSizeVars.joinToString("") { " + ${it.second}" })
                    addStatement("$T $L = new String[$L]",
                            String::class.arrayTypeName(), outArgsName, argCount)
                }
            } else {
                addStatement("$T $L = $S", String::class.typeName(),
                        outSqlQueryName, queryMethod.query.queryWithReplacedBindParams)
                if (outArgsName != null) {
                    addStatement("$T $L = new String[$L]",
                            String::class.arrayTypeName(), outArgsName, knownQueryArgsCount)
                }
            }
        }
        return listSizeVars
    }

    fun bindArgs(outArgsName: String, listSizeVars : List<Pair<QueryParameter, String>>,
                         scope: CodeGenScope) {
        if (queryMethod.parameters.isEmpty()) {
            return
        }
        scope.builder().apply {
            val argIndex = scope.getTmpVar("_argIndex")
            val startIndex = if (queryMethod.query.type == QueryType.SELECT) 0 else 1
            addStatement("$T $L = $L", TypeName.INT, argIndex, startIndex)
            // # of bindings with 1 placeholder
            var constInputs = 0
            // variable names for size of the bindings that have multiple  args
            val varInputs = arrayListOf<String>()
            queryMethod.sectionToParamMapping.forEach { pair ->
                // reset the argIndex to the correct start index
                if (constInputs > 0 || varInputs.isNotEmpty()) {
                    addStatement("$L = $L$L$L$L", argIndex,
                            if (startIndex > 0) "$startIndex + " else "",
                            if (constInputs > 0) constInputs else "",
                            if (constInputs > 0 && varInputs.isNotEmpty()) " + " else "",
                            varInputs.joinToString(" + "))
                }
                val param = pair.second
                param?.let {
                    if (queryMethod.query.type == QueryType.SELECT) {
                        param.queryParamAdapter?.convert(param.name, outArgsName, argIndex, scope)
                    } else {
                        param.queryParamAdapter?.bindToStmt(param.name, outArgsName, argIndex,
                                scope)
                    }
                }
                // add these to the list so that we can use them to calculate the next count.
                val sizeVar = listSizeVars.firstOrNull { it.first == param }
                if (sizeVar == null) {
                    constInputs ++
                } else {
                    varInputs.add(sizeVar.second)
                }
            }
        }
    }
}
