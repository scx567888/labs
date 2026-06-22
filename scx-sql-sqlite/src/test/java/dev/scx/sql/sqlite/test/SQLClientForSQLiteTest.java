package dev.scx.sql.sqlite.test;

import dev.scx.jdbc.spy.ScxJdbcSpy;
import dev.scx.jdbc.spy.listener.logging.LoggingDataSourceListener;
import dev.scx.jdbc.spy.listener.logging.PreparedStatementLogStyle;
import dev.scx.logging.ScxLoggerConfig;
import dev.scx.logging.ScxLogging;
import dev.scx.sql.SQLClient;
import dev.scx.sql.UpdateResult;
import dev.scx.sql.sqlite.test.bean.Student;
import dev.scx.sql.sqlite.test.bean.StudentRecord;
import org.sqlite.SQLiteDataSource;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.scx.sql.BatchSQL.batchSQL;
import static dev.scx.sql.SQL.sql;
import static dev.scx.sql.extractor.ResultSetExtractor.*;
import static java.lang.System.Logger.Level.DEBUG;

public class SQLClientForSQLiteTest {

    public static final DataSource dataSource;
    private static final Path TempSQLite;
    private static final SQLClient sqlClient;
    private static final String tableName = "t1";

    static {
        ScxLogging.setConfig("ScxJdbcSpy", new ScxLoggerConfig().setLevel(DEBUG));
        try {
            var tempDir = Path.of(System.getProperty("java.io.tmpdir"));
            TempSQLite = tempDir.resolve("scx_sql_temp").resolve("temp.sqlite");
            Files.createDirectories(TempSQLite.getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        dataSource = getSQLiteDataSource();
        sqlClient = SQLClient.of(dataSource);
    }

    public static void main(String[] args) throws SQLException {
        beforeTest();
        test1();
        test2();
        test3();
        test4();
        test5();
    }

    @BeforeTest
    public static void beforeTest() {
        try {
            sqlClient.execute(sql("drop table if exists " + tableName + ";"));
            sqlClient.execute(sql("create table " + tableName + "(`name` text unique ,`age` integer,`sex` integer )"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public static void test1() throws SQLException {
        var sql = "insert into " + tableName + "(name, age, sex) values (?, ?, ?)";
        var m = new Object[]{"小蓝", 22, 1};
        UpdateResult update = sqlClient.update(sql(sql, m));
        System.out.println("占位符参数插入单条数据 : " + update);
        var ms = new ArrayList<Object[]>();
        for (int i = 0; i < 999; i = i + 1) {
            var m1 = new Object[]{"小蓝" + i, 22 + i, 0};
            ms.add(m1);
        }
        UpdateResult update1 = sqlClient.update(batchSQL(sql, ms));
        System.out.println("占位符参数批量插入多条数据 : " + update1);
    }

    @Test
    public static void test2() throws SQLException {
        List<Student> query = sqlClient.query(sql("select * from " + tableName), ofBeanList(Student.class));
        System.out.println("查询 使用 BeanList 总条数: " + query.size());
        System.out.println("查询 使用 BeanList 第一条内容: " + query.get(0));
        List<Map<String, Object>> query1 = sqlClient.query(sql("select * from `t1`"), ofMapList());
        System.out.println("查询 使用 MapList 总条数: " + query1.size());
        System.out.println("查询 使用 MapList 第一条内容: " + query1.get(0));
    }

    @Test
    public static void test3() throws SQLException {
        List<Student> query = sqlClient.query(sql("select * from " + tableName), ofBeanList(Student.class));
        System.out.println("当前总条数: " + query.size());
        try {
            sqlClient.autoTransaction(() -> {

                var sql = "insert into " + tableName + "(name, age, sex) values (?, ?, ?)";
                var m = new Object[]{"小李", 22, 1};

                sqlClient.update(sql(sql, m));
                List<Student> query1 = sqlClient.query(sql("select * from " + tableName), ofBeanList(Student.class));
                System.out.println("当前总条数: " + query1.size());

                sqlClient.update(sql(sql, m));
            });
        } catch (Exception e) {
            System.err.println("成功捕获到异常 : " + e);
        }
        List<StudentRecord> query2 = sqlClient.query(sql("select * from " + tableName), ofBeanList(StudentRecord.class));
        System.out.println("回滚后总条数: " + query2.size());
    }

    @Test
    public static void test4() throws SQLException {
        var sql = sql("select * from " + tableName);
        //测试多种 ResultHandler

        //查询单个
        var ofMap = sqlClient.query(sql, ofMap());
        System.out.println("ofMap " + ofMap);
        var ofMap1 = sqlClient.query(sql, ofMap(() -> new LinkedHashMap<>()));
        System.out.println("ofMap1 " + ofMap1);
        var ofBean = sqlClient.query(sql, ofBean(StudentRecord.class));
        System.out.println("ofBean " + ofBean);
        var ofBean1 = sqlClient.query(sql, ofBean(StudentRecord.class, (c) -> null));
        System.out.println("ofBean1 " + ofBean1);


        //查询多个
        var ofMapList = sqlClient.query(sql, ofMapList());
        System.out.println("ofMapList " + ofMapList.size());
        var ofMapList1 = sqlClient.query(sql, ofMapList(() -> new LinkedHashMap<>()));
        System.out.println("ofMapList1 " + ofMapList1.size());
        var ofBeanList = sqlClient.query(sql, ofBeanList(StudentRecord.class));
        System.out.println("ofBeanList " + ofBeanList.size());
        var ofBeanList1 = sqlClient.query(sql, ofBeanList(StudentRecord.class, (c) -> null));
        System.out.println("ofBeanList1 " + ofBeanList1.size());

        var size = new AtomicInteger();
        //使用 消费者 直接处理
        sqlClient.query(sql, ofMapConsumer(x -> size.getAndIncrement()));
        System.out.println("ofMapConsumer " + size);
        size.set(0);
        sqlClient.query(sql, ofMapConsumer(() -> new LinkedHashMap<>(), x -> size.getAndIncrement()));
        System.out.println("ofMapConsumer1 " + size);
        size.set(0);
        sqlClient.query(sql, ofBeanConsumer(StudentRecord.class, x -> size.getAndIncrement()));
        System.out.println("ofBeanConsumer " + size);
        size.set(0);
        sqlClient.query(sql, ofBeanConsumer(StudentRecord.class, (c) -> null, x -> size.getAndIncrement()));
        System.out.println("ofBeanConsumer1 " + size);
    }

    @Test
    public static void test5() throws SQLException {
        // 太费时 所以暂不执行
        if (true) {
            return;
        }

        sqlClient.execute(sql("drop table if exists " + tableName + ";"));
        sqlClient.execute(sql("create table " + tableName + "(`name` varchar(32) ,`age` integer,`sex` boolean )"));

        try { // 准备大量数据 200万条 进行测试
            var sql = "insert into " + tableName + "(name, age, sex) values (:name, :age, :sex)";
            var ms = new ArrayList<Object[]>();
            for (int i = 0; i < 99999; i = i + 1) {
                var m1 = new Object[]{"小明" + i, 18 + i, 0};
                ms.add(m1);
            }
            for (int j = 0; j < 20; j = j + 1) {
                UpdateResult update1 = sqlClient.update(batchSQL(sql, ms));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        var sql = sql("select * from " + tableName);

        Runtime.getRuntime().gc();

        //测试内存占用

        for (int i = 0; i < 10; i = i + 1) {
            var s = System.nanoTime();
            var ofBeanList = sqlClient.query(sql, ofBeanList(StudentRecord.class));
            System.out.println("ofBeanList 耗时 : " + (System.nanoTime() - s) / 1000_000 + " 内存占用 : " + Runtime.getRuntime().totalMemory() + " ; " + ofBeanList.size());
        }

        Runtime.getRuntime().gc();

        for (int i = 0; i < 10; i = i + 1) {
            var s = System.nanoTime();
            var size = new AtomicInteger();
            sqlClient.query(sql, ofBeanConsumer(StudentRecord.class, x -> {
                size.set(size.get() + 1);
            }));
            System.out.println("ofBeanConsumer 耗时 : " + (System.nanoTime() - s) / 1000_000 + " 内存占用 : " + Runtime.getRuntime().totalMemory() + " ; " + size);
        }
    }

    private static DataSource getSQLiteDataSource() {
        SQLiteDataSource sqLiteDataSource = new SQLiteDataSource();
        sqLiteDataSource.setUrl("jdbc:sqlite:" + TempSQLite);
        return ScxJdbcSpy.spy(sqLiteDataSource, new LoggingDataSourceListener(PreparedStatementLogStyle.RENDERED_SQL));
    }

}
