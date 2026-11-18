package studyroom;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 所有 SQL 均使用占位参数；统一 H2 的列名和时间表示。 */
@Repository
public class Store {
    private final JdbcTemplate jdbc;
    public Store(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> list(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> {
            Map<String,Object> result = new LinkedHashMap<>();
            var meta = rs.getMetaData();
            for (int i=1; i<=meta.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof Timestamp ts) value = ts.toLocalDateTime();
                if (value instanceof java.sql.Date date) value = date.toLocalDate();
                result.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
            }
            return result;
        }, args);
    }
    public Map<String,Object> one(String sql, Object... args) {
        var rows = list(sql, args);
        if (rows.isEmpty()) throw new BusinessException(404, "记录不存在");
        return rows.get(0);
    }
    public long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
    public long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i=0; i<args.length; i++) statement.setObject(i+1, args[i]);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public void lock() { jdbc.queryForObject("SELECT id FROM booking_mutex WHERE id=1 FOR UPDATE", Integer.class); }
    public static long id(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    public static boolean flag(Map<String,Object> row, String key) {
        Object value = row.get(key); return value instanceof Boolean b ? b : ((Number)value).intValue()!=0;
    }
    public static LocalDateTime time(Map<String,Object> row, String key) { return (LocalDateTime)row.get(key); }
}
