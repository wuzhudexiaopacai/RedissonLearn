package com.github.wuzhudexiaopacai.redisson.common.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * SpEL key 解析器：把注解中的 SpEL 表达式解析为最终的锁 key。
 * 方法参数名会作为变量注入上下文（需要编译时开启 -parameters，父 POM 已配置）。
 *
 * @author wuzhudexiaopacai
 */
@Component
public class SpelKeyResolver {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 解析 SpEL 表达式，方法参数以参数名作为变量暴露。
     *
     * @param joinPoint 切点
     * @param spel      表达式，如 "#userId + ':' + #orderId"
     * @return 解析后的字符串 key
     */
    public String resolve(ProceedingJoinPoint joinPoint, String spel) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        EvaluationContext context = new StandardEvaluationContext();
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        Object value = parser.parseExpression(spel).getValue(context);
        if (value == null) {
            throw new IllegalStateException("SpEL 表达式 [" + spel + "] 解析结果为 null");
        }
        return String.valueOf(value);
    }
}
