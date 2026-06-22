package dev.scx.sql.sqlite.test.bean;

public record StudentRecord(String name, Integer age, Boolean sex) {

    //注意这里 !!! 其他的构造函数不会影响 Record 的创建
    public StudentRecord(String name, Integer age) {
        this(name, age, false);
    }

    public StudentRecord(String name, Boolean sex, Integer age) {
        this(name, age, false);
    }

}
