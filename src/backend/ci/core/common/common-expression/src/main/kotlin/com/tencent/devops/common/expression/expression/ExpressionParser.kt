package com.tencent.devops.common.expression.expression

import com.tencent.devops.common.expression.InvalidOperationException
import com.tencent.devops.common.expression.NotSupportedException
import com.tencent.devops.common.expression.ParseException
import com.tencent.devops.common.expression.expression.Stack.Companion.peek
import com.tencent.devops.common.expression.expression.Stack.Companion.pop
import com.tencent.devops.common.expression.expression.Stack.Companion.push
import com.tencent.devops.common.expression.expression.functions.NoOperation
import com.tencent.devops.common.expression.expression.sdk.Container
import com.tencent.devops.common.expression.expression.sdk.ExpressionNode
import com.tencent.devops.common.expression.expression.sdk.Function
import com.tencent.devops.common.expression.expression.sdk.NoOperationNamedValue
import com.tencent.devops.common.expression.expression.sdk.operators.And
import com.tencent.devops.common.expression.expression.sdk.operators.Or
import com.tencent.devops.common.expression.expression.tokens.Associativity
import com.tencent.devops.common.expression.expression.tokens.LexicalAnalyzer
import com.tencent.devops.common.expression.expression.tokens.Token
import com.tencent.devops.common.expression.expression.tokens.TokenKind
import java.util.TreeMap

@Suppress("NestedBlockDepth", "ComplexCondition", "EmptyFunctionBlock")
class ExpressionParser {

    fun createTree(
        expression: String,
        trace: ITraceWriter?,
        namedValues: Iterable<INamedValueInfo>?,
        functions: Iterable<IFunctionInfo>?
    ): IExpressionNode? {
        val context = ParseContext(expression, trace, namedValues, functions)
        context.trace.info("Parsing expression: <$expression>")
        return createTree(context)
    }

    fun validateSyntax(
        expression: String,
        trace: ITraceWriter?
    ): IExpressionNode? {
        val context = ParseContext(expression, trace, null, null, true)
        context.trace.info("Validating expression syntax: <$expression>")
        return createTree(context)
    }

    companion object {
        private fun createTree(context: ParseContext): IExpressionNode? {
            // 推入 token
            while (true) {
                val (token, ok) = context.lexicalAnalyzer.tryGetNextToken()
                if (!ok) {
                    break
                }
                context.token = token

                // Unexpected
                if (context.token?.kind == TokenKind.Unexpected) {
                    throw ParseException(ParseExceptionKind.UnexpectedSymbol, context.token, context.expression)
                } else if (context.token?.isOperator == true) {
                    pushOperator(context)
                } else {
                    pushOperand(context)
                }

                context.lastToken = context.token
            }

            // No tokens
            if (context.lastToken == null) {
                return null
            }

            // Check unexpected end of expression
            if (context.operators.isNotEmpty()) {
                var unexpectedLastToken = false
                when (context.lastToken?.kind) {
                    TokenKind.EndGroup, // ")" logical grouping
                    TokenKind.EndIndex, // "]"
                    TokenKind.EndParameters -> { // ")" function call
                        // Legal
                    }

                    TokenKind.Function -> {
                        // Illegal
                        unexpectedLastToken = true
                    }
                    else -> {
                        unexpectedLastToken = context.lastToken?.isOperator == true
                    }
                }

                if (unexpectedLastToken || context.lexicalAnalyzer.unclosedTokens.any()) {
                    throw ParseException(
                        ParseExceptionKind.UnexpectedEndOfExpression,
                        context.lastToken,
                        context.expression
                    )
                }
            }

            // Flush operators
            while (context.operators.isNotEmpty()) {
                flushTopOperator(context)
            }

            // Check max depth
            val result = context.operands.single()
            checkMaxDepth(context, result)

            return result
        }

        private fun pushOperand(context: ParseContext) {
            // Create the node
            val node: ExpressionNode?
            when (context.token?.kind) {
                // Function
                TokenKind.Function -> {
                    val function = context.token?.rawValue
                    val (functionInfo, ok) = tryGetFunctionInfo(context, function)
                    if (ok) {
                        node = functionInfo!!.createNode()
                        node.name = function ?: ""
                    } else if (context.allowUnknownKeywords) {
                        node = NoOperation()
                        node.name = function ?: ""
                    } else {
                        throw ParseException(
                            ParseExceptionKind.UnrecognizedFunction,
                            context.token,
                            context.expression
                        )
                    }
                }

                // Named-value
                TokenKind.NamedValue -> {
                    val name = context.token?.rawValue
                    if (context.extensionNamedValues[name] != null) {
                        node = context.extensionNamedValues[name]?.createNode()
                        node?.name = name ?: ""
                    } else if (context.allowUnknownKeywords) {
                        node = NoOperationNamedValue()
                        node.name = name ?: ""
                    } else {
                        throw ParseException(
                            ParseExceptionKind.UnrecognizedNamedValue,
                            context.token,
                            context.expression
                        )
                    }
                }

                // Otherwise simple
                else -> {
                    node = context.token?.toNode()
                }
            }

            // Push the operand
            context.operands.push(node!!)
        }

        private fun pushOperator(context: ParseContext) {
            // 刷新更高或相等的优先级
            if (context.token!!.associativity == Associativity.LeftToRight) {
                val precedence = context.token!!.precedence
                while (context.operators.isNotEmpty()) {
                    val topOperator = context.operators.peek()
                    if (precedence <= topOperator.precedence &&
                        topOperator.kind != TokenKind.StartGroup && // Unless top is "(" logical grouping
                        topOperator.kind != TokenKind.StartIndex && // or unless top is "["
                        topOperator.kind != TokenKind.StartParameters && // or unless top is "(" function call
                        topOperator.kind != TokenKind.Separator
                    ) // or unless top is ","
                        {
                            flushTopOperator(context)
                            continue
                        }

                    break
                }
            }

            // Push the operator
            context.operators.push(context.token!!)

            // 处理关闭操作符，因为 context.LastToken 是必需的
            // 为了准确处理 TokenKind.EndParameters
            when (context.token!!.kind) {
                TokenKind.EndGroup, // ")" logical grouping
                TokenKind.EndIndex, // "]"
                TokenKind.EndParameters -> // ")" function call
                    flushTopOperator(context)
            }
        }

        private fun flushTopOperator(context: ParseContext) {
            // Special handling for closing operators
            when (context.operators.peek().kind) {
                TokenKind.EndIndex -> { // "]"
                    flushTopEndIndex(context)
                    return
                }

                TokenKind.EndGroup -> { // ")" logical grouping
                    flushTopEndGroup(context)
                    return
                }

                TokenKind.EndParameters -> { // ")" function call
                    flushTopEndParameters(context)
                    return
                }
            }

            // Pop the operator
            val operator = context.operators.pop()

            // Create the node
            val node = operator.toNode() as Container

            // Pop the operands, add to the node
            val operands = popOperands(context, operator.operandCount)
            operands.forEach { operand ->
                // Flatten nested And
                if (node is And) {
                    if (operand is And) {
                        operand.parameters.forEach { nestedParameter ->
                            node.addParameters(nestedParameter)
                        }

                        return@forEach
                    }
                }
                // Flatten nested Or
                else if (node is Or) {
                    if (operand is Or) {
                        operand.parameters.forEach { nestedParameter ->
                            node.addParameters(nestedParameter)
                        }

                        return@forEach
                    }
                }

                node.addParameters(operand)
            }

            // Push the node to the operand stack
            context.operands.push(node)
        }

        // / <summary>
        // / Flushes the ")" logical grouping operator
        // / </summary>
        private fun flushTopEndGroup(context: ParseContext) {
            // Pop the operators
            // ")" logical grouping
            popOperator(context, TokenKind.EndGroup)
            // "(" logical grouping
            popOperator(context, TokenKind.StartGroup)
        }

        // / <summary>
        // / Flushes the "]" operator
        // / </summary>
        private fun flushTopEndIndex(context: ParseContext) {
            // Pop the operators
            // "]"
            popOperator(context, TokenKind.EndIndex)
            val operator = popOperator(context, TokenKind.StartIndex) // "["

            // Create the node
            val node = operator.toNode() as Container

            // Pop the operands, add to the node
            val operands = popOperands(context, operator.operandCount)
            operands.forEach { operand ->
                node.addParameters(operand)
            }

            // Push the node to the operand stack
            context.operands.push(node)
        }

        // ")" function call
        private fun flushTopEndParameters(context: ParseContext) {
            // Pop the operator
            // ")" function call
            var operator = popOperator(context, TokenKind.EndParameters)

            // Sanity check top operator is the current token
            if (operator != context.token) {
                throw InvalidOperationException("Expected the operator to be the current token")
            }

            val function: Function?

            // No parameters
            if (context.lastToken?.kind == TokenKind.StartParameters) {
                // Node already exists on the operand stack
                function = context.operands.peek() as Function
            }
            // Has parameters
            else {
                // Pop the operands
                var parameterCount = 1
                while (context.operators.peek().kind == TokenKind.Separator) {
                    parameterCount++
                    context.operators.pop()
                }
                val functionOperands = popOperands(context, parameterCount)

                // Node already exists on the operand stack
                function = context.operands.peek() as Function

                // Add the operands to the node
                functionOperands.forEach { operand ->
                    function.addParameters(operand)
                }
            }

            // Pop the "(" operator too
            operator = popOperator(context, TokenKind.StartParameters)

            // Check min/max parameter count
            val (functionInfo, ok) = tryGetFunctionInfo(context, function.name)
            if (!ok) {
                throw NotSupportedException("not get function ${function.name} info")
            }

            if (functionInfo == null && context.allowUnknownKeywords) {
                // Don't check min/max
            } else if (function.parameters.count() < functionInfo!!.minParameters) {
                throw ParseException(ParseExceptionKind.TooFewParameters, operator, context.expression)
            } else if (function.parameters.count() > functionInfo.maxParameters) {
                throw ParseException(ParseExceptionKind.TooManyParameters, operator, context.expression)
            }
        }

        // / <summary>
        // / Pops N operands from the operand stack. The operands are returned
        // / in their natural listed order, i.e. not last-in-first-out.
        // / </summary>
        private fun popOperands(
            context: ParseContext,
            c: Int
        ): List<ExpressionNode> {
            var count = c
            val result = mutableListOf<ExpressionNode>()
            while (count-- > 0) {
                result.add(context.operands.pop())
            }

            result.reverse()
            return result
        }

        // / <summary>
        // / Pops an operator and asserts it is the expected kind.
        // / </summary>
        private fun popOperator(
            context: ParseContext,
            expected: TokenKind
        ): Token {
            val token = context.operators.pop()
            if (token.kind != expected) {
                throw NotSupportedException("Expected operator '$expected' to be popped. Actual '${token.kind}'.")
            }
            return token
        }

        // / <summary>
        // / Checks the max depth of the expression tree
        // / </summary>
        private fun checkMaxDepth(
            context: ParseContext,
            node: ExpressionNode,
            depth: Int = 1
        ) {
            if (depth > ExpressionConstants.MAX_DEEP) {
                throw ParseException(ParseExceptionKind.ExceededMaxDepth, null, context.expression)
            }

            if (node is Container) {
                node.parameters.forEach { parameter ->
                    checkMaxDepth(context, parameter, depth + 1)
                }
            }
        }

        private fun tryGetFunctionInfo(
            context: ParseContext,
            name: String?
        ): Pair<IFunctionInfo?, Boolean> {
            var result = ExpressionConstants.WELL_KNOWN_FUNCTIONS[name]
            if (result != null) {
                return Pair(result, true)
            }
            result = context.extensionFunctions[name]
            if (result != null) {
                return Pair(result, true)
            }

            return Pair(null, false)
        }
    }

    class ParseContext(
        val expression: String,
        trace: ITraceWriter?,
        namedValues: Iterable<INamedValueInfo>?,
        functions: Iterable<IFunctionInfo>?,
        val allowUnknownKeywords: Boolean = false
    ) {
        val extensionFunctions = TreeMap<String, IFunctionInfo>(String.CASE_INSENSITIVE_ORDER)
        val extensionNamedValues = TreeMap<String, INamedValueInfo>(String.CASE_INSENSITIVE_ORDER)
        val lexicalAnalyzer: LexicalAnalyzer
        val operands = ArrayDeque<ExpressionNode>()
        val operators = ArrayDeque<Token>()
        val trace: ITraceWriter
        var token: Token? = null
        var lastToken: Token? = null

        init {
            if (expression.length > ExpressionConstants.MAX_LENGTH) {
                throw ParseException(ParseExceptionKind.ExceededMaxLength, null, expression)
            }

            namedValues?.forEach { namedValueInfo ->
                extensionNamedValues[namedValueInfo.name] = namedValueInfo
            }

            functions?.forEach { functionInfo ->
                extensionFunctions[functionInfo.name] = functionInfo
            }

            this.trace = trace ?: NoOperationTraceWriter()

            lexicalAnalyzer = LexicalAnalyzer(expression)
        }

        private class NoOperationTraceWriter : ITraceWriter {
            override fun info(message: String?) {}
            override fun verbose(message: String?) {}
        }
    }
}
