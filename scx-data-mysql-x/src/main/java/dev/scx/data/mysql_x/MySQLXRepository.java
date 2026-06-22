package dev.scx.data.mysql_x;

import com.mysql.cj.xdevapi.DbDoc;
import com.mysql.cj.xdevapi.Schema;
import dev.scx.data.Finder;
import dev.scx.data.Repository;
import dev.scx.data.field_policy.FieldPolicy;
import dev.scx.data.query.Query;
import dev.scx.node.ObjectNode;
import dev.scx.serialize.ScxSerialize;

import java.util.Collection;
import java.util.List;

import static dev.scx.data.mysql_x.JsonHelper.*;
import static dev.scx.data.mysql_x.parser.MySQLXDaoWhereParser.WHERE_PARSER;

/**
 * 使用 MySQL X Dev Api 通过 MySQL X 协议, 操作 MySQL 的 Dao
 *
 * @param <Entity>
 * @author scx567888
 * @version 0.0.1
 */
public class MySQLXRepository<Entity> implements Repository<Entity, String> {

    final com.mysql.cj.xdevapi.Collection collection;
    final Class<Entity> entityClass;

    public MySQLXRepository(Class<Entity> entityClass, com.mysql.cj.xdevapi.Collection collection) {
        this.entityClass = entityClass;
        this.collection = collection;
    }

    public MySQLXRepository(Class<Entity> entityClass, Schema schema) {
        this(entityClass, schema.createCollection(initCollectionName(entityClass), true));
    }

    public static String initCollectionName(Class<?> clazz) {
        var scxModel = clazz.getAnnotation(dev.scx.data.mysql_x.annotation.Collection.class);
        if (scxModel == null) {
            throw new IllegalArgumentException("@Collection annotation not found");
        }
        return scxModel.value();
    }

    public static DbDoc toDbDoc(Object entity, FieldPolicy updateFilter) {
        var jsonNode = ScxSerialize.objectToNode(entity);
        if (jsonNode instanceof ObjectNode objectNode) {
            var newObjectNode = filterObjectNode(objectNode, updateFilter);
            return (DbDoc) toJsonValue(newObjectNode);
        }
        throw new IllegalArgumentException("jsonNode 类型不为 ObjectNode !!!");
    }

    @Override
    public String add(Entity entity, FieldPolicy updateFilter) {
        var dbDoc = toDbDoc(entity, updateFilter.exclude("_id"));
        var addResult = this.collection.add(dbDoc).execute();
        var generatedIds = addResult.getGeneratedIds();
        return generatedIds.get(0);
    }

    @Override
    public List<String> add(Collection<Entity> entityList, FieldPolicy updateFilter) {
        var dbDocs = new DbDoc[entityList.size()];
        var index = 0;
        for (var entity : entityList) {
            dbDocs[index] = toDbDoc(entity, updateFilter.exclude("_id"));
            index = index + 1;
        }
        var addResult = this.collection.add(dbDocs).execute();
        return addResult.getGeneratedIds();
    }

    @Override
    public Finder<Entity> finder(Query query, FieldPolicy fieldPolicy) {
        return new MySQLXFinder<>(this, query, fieldPolicy);
    }

    @Override
    public long update(Entity entity, FieldPolicy updateFilter, Query query) {
        var whereClause = WHERE_PARSER.parse(query.getWhere());
        var newDoc = toDbDoc(entity, updateFilter.exclude("_id"));
        var result = this.collection
            .modify(whereClause.expression())
            .bind(whereClause.params())
            .patch(newDoc)
            .execute();
        return result.getAffectedItemsCount();
    }

    @Override
    public long delete(Query query) {
        var whereClause = WHERE_PARSER.parse(query.getWhere());
        var result = this.collection
            .remove(whereClause.expression())
            .bind(whereClause.params())
            .execute();
        return result.getAffectedItemsCount();
    }

    @Override
    public void clear() {
        this.collection.remove("TRUE").execute();
    }

    public Entity toEntity(DbDoc dbDoc, FieldPolicy filter) {
        var newDbDoc = filterDbDoc(dbDoc, filter);
        var objectNode = toObjectNode(newDbDoc);
        return ScxSerialize.convertObject(objectNode, entityClass);
    }

}
