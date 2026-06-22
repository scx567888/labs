package dev.scx.data.mysql_x;

import dev.scx.data.Finder;
import dev.scx.data.exception.DataAccessException;
import dev.scx.data.field_policy.FieldPolicy;
import dev.scx.data.query.Query;
import dev.scx.exception.ScxWrappedException;
import dev.scx.function.Function1Void;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.scx.data.mysql_x.parser.MySQLXDaoWhereParser.WHERE_PARSER;

public class MySQLXFinder<Entity> implements Finder<Entity> {

    private final MySQLXRepository<Entity> repository;
    private final Query query;
    private final FieldPolicy fieldPolicy;

    public MySQLXFinder(MySQLXRepository<Entity> repository, Query query, FieldPolicy fieldPolicy) {
        this.repository = repository;
        this.query = query;
        this.fieldPolicy = fieldPolicy;
    }

    @Override
    public List<Entity> list() {
        var whereClause = WHERE_PARSER.parse(query.getWhere());
        var findStatement = repository.collection
            .find(whereClause.expression())
            .bind(whereClause.params());
        if (query.getOffset() != null) {
            findStatement.offset(query.getOffset());
        }
        if (query.getLimit() != null) {
            findStatement.limit(query.getLimit());
        }
        var docResult = findStatement.execute();
        var dbDocs = docResult.fetchAll();
        var list = new ArrayList<Entity>();
        for (var dbDoc : dbDocs) {
            list.add(repository.toEntity(dbDoc, fieldPolicy));
        }
        return list;
    }

    @Override
    public <T> List<T> list(Class<T> resultType) {
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public List<Map<String, Object>> listMap() {
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public void forEach(Function1Void<Entity, ?> entityConsumer) throws DataAccessException, ScxWrappedException {
        var whereClause = WHERE_PARSER.parse(query.getWhere());
        var findStatement = repository.collection
            .find(whereClause.expression())
            .bind(whereClause.params());
        if (query.getOffset() != null) {
            findStatement.offset(query.getOffset());
        }
        if (query.getLimit() != null) {
            findStatement.limit(query.getLimit());
        }
        var docResult = findStatement.execute();
        for (var dbDoc : docResult) {
            try {
                entityConsumer.apply(repository.toEntity(dbDoc, fieldPolicy));
            } catch (Throwable e) {
                throw new ScxWrappedException(e);
            }
        }
    }

    @Override
    public <T> void forEach(Function1Void<T, ?> entityConsumer, Class<T> resultType) throws DataAccessException, ScxWrappedException {
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public void forEachMap(Function1Void<Map<String, Object>, ?> entityConsumer) throws DataAccessException, ScxWrappedException {
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public Entity first() {
        var whereClause = WHERE_PARSER.parse(query.getWhere());
        var findStatement = repository.collection
            .find(whereClause.expression())
            .bind(whereClause.params())
            .limit(1);

        var docResult = findStatement.execute();
        var dbDoc = docResult.fetchOne();

        return repository.toEntity(dbDoc, fieldPolicy);
    }

    @Override
    public <T> T first(Class<T> resultType) {
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public Map<String, Object> firstMap() {
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public long count() {
        var whereClause = WHERE_PARSER.parse(query.getWhere());
        var docResult = repository.collection
            .find(whereClause.expression())
            .bind(whereClause.params())
            .execute();
        return docResult.count();
    }

}
