package dev.scx.data.mysql_x.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ScxModel
 * model 层映射
 * 设置此注解的方法 必须同时 继承 BaseModel
 *
 * @author scx567888
 * @version 0.0.1
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Collection {

    /**
     * 表名称
     *
     * @return 表全限定名称
     */
    String value();

}
