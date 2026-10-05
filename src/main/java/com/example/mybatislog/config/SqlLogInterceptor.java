package com.example.mybatislog.config;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.springframework.stereotype.Component;

/**
 * MyBatis 실행 SQL 로그 인터셉터 (추가 라이브러리 없이 MyBatis Plugin 만 사용).
 * - XML 에 작성한 줄바꿈/들여쓰기를 유지하고 ? 자리에 실제 파라미터 값을 넣어 출력
 * - 조회 결과를 표 형태로 출력
 * logging.level.com.example.mybatislog.config.SqlLogInterceptor=debug 일 때만 동작한다.
 */
@Slf4j
@Component
@Intercepts({
  @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
  @Signature(type = StatementHandler.class, method = "queryCursor", args = {Statement.class}),
  @Signature(type = StatementHandler.class, method = "update", args = {Statement.class}),
  @Signature(type = StatementHandler.class, method = "batch", args = {Statement.class})
})
public class SqlLogInterceptor implements Interceptor {

  // BLOB, RAW
  private static final Set<Integer> BINARY_TYPES = Set.of(Types.BLOB, Types.BINARY, Types.VARBINARY);
  // Oracle LONG, LONG RAW: 스트림 컬럼이라 뒤 컬럼을 먼저 읽으면 MyBatis 가 값을 읽지 못함
  private static final Set<Integer> LONG_TYPES = Set.of(Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.LONGVARBINARY);
  // ponytail: 문자 체계 기준 근사치. 전각 영문/이모지 등은 1칸으로 계산되어 표 정렬이 어긋날 수 있음
  private static final Set<Character.UnicodeScript> WIDE_SCRIPTS = EnumSet.of(
      Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN,
      Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA);

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    if (!log.isDebugEnabled()) {
      return invocation.proceed();
    }

    StatementHandler handler = (StatementHandler) invocation.getTarget();
    // ponytail: RoutingStatementHandler 내부 필드 접근. StatementHandler 를 감싸는 다른 플러그인이 먼저 적용되면 Proxy 언랩 필요
    MappedStatement ms = (MappedStatement) SystemMetaObject.forObject(handler).getValue("delegate.mappedStatement");
    String sql = toSql(ms.getConfiguration(), handler.getBoundSql(), handler.getParameterHandler().getParameterObject());

    List<ResultTable> tables = new ArrayList<>();
    if ("query".equals(invocation.getMethod().getName())) {
      Object[] args = invocation.getArgs();
      args[0] = captureResults((Statement) args[0], tables);
    }

    long start = System.currentTimeMillis();
    Object result = null;
    try {
      result = invocation.proceed();
      return result;
    } finally {
      // 실패한 쿼리도 SQL 은 남긴다
      long elapsed = System.currentTimeMillis() - start;
      String summary = result instanceof Integer count
          ? "\naffected rows: " + count
          : tables.stream().map(ResultTable::render).collect(Collectors.joining());
      log.debug("[{}] {}ms\n{}{}", ms.getId(), elapsed, sql, summary);
    }
  }

  // XML 줄바꿈 유지 + ? 자리에 실제 값 치환
  private static String toSql(Configuration config, BoundSql boundSql, Object param) {
    String sql = prettify(boundSql.getSql());
    List<ParameterMapping> mappings = boundSql.getParameterMappings();
    TypeHandlerRegistry registry = config.getTypeHandlerRegistry();
    MetaObject meta = param == null ? null : config.newMetaObject(param);

    StringBuilder out = new StringBuilder();
    int index = 0;
    boolean inLiteral = false;
    for (char c : sql.toCharArray()) {
      if (c == '\'') {
        inLiteral = !inLiteral;
      }
      if (c != '?' || inLiteral || index >= mappings.size()) {
        out.append(c);
        continue;
      }
      // DefaultParameterHandler 와 동일한 값 조회 규칙
      String property = mappings.get(index++).getProperty();
      Object value;
      if (boundSql.hasAdditionalParameter(property)) {
        value = boundSql.getAdditionalParameter(property);
      } else if (param == null || registry.hasTypeHandler(param.getClass())) {
        value = param;
      } else {
        value = meta.getValue(property);
      }
      out.append(toLiteral(value));
    }
    return out.toString();
  }

  // MyBatis 가 SQL 을 trim 해서 첫 줄은 들여쓰기가 없음 → 빈 줄 제거 + 둘째 줄부터 공통 들여쓰기 제거
  // ponytail: 첫 줄의 원래 들여쓰기는 알 수 없어 river 스타일(SELECT/  FROM/ WHERE 우측 정렬)은 한 칸 정도 어긋남
  private static String prettify(String sql) {
    List<String> lines = sql.lines().filter(line -> !line.isBlank()).toList();
    if (lines.size() < 2) {
      return sql.strip();
    }
    return lines.get(0).stripTrailing() + "\n" + String.join("\n", lines.subList(1, lines.size())).stripIndent();
  }

  // Oracle 에서 그대로 실행 가능한 리터럴로 변환
  private static String toLiteral(Object value) {
    return switch (value) {
      case null -> "NULL";
      case Number number -> number.toString();
      // Oracle SQL 에는 boolean 리터럴이 없고 JDBC 는 1/0 으로 바인딩
      case Boolean bool -> bool ? "1" : "0";
      case LocalDate date -> "DATE '" + date + "'";
      case LocalDateTime dateTime -> "TIMESTAMP '" + Timestamp.valueOf(dateTime) + "'";
      case java.sql.Date date -> "DATE '" + date + "'";
      case Date date -> "TIMESTAMP '" + new Timestamp(date.getTime()) + "'";
      default -> "'" + value.toString().replace("'", "''") + "'";
    };
  }

  // Statement 가 돌려주는 ResultSet(getResultSet, 프로시저 REF CURSOR 의 getObject)을 감싸 next() 마다 행을 수집
  private static Statement captureResults(Statement statement, List<ResultTable> tables) {
    Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
        : statement instanceof PreparedStatement ? PreparedStatement.class
        : Statement.class;
    return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
      Object result = invoke(statement, method, args);
      if (!(result instanceof ResultSet rs)) {
        return result;
      }
      ResultTable table = new ResultTable();
      tables.add(table);
      return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (p, m, a) -> {
        Object hasNext = invoke(rs, m, a);
        if ("next".equals(m.getName())) {
          table.capture(rs, (Boolean) hasNext);
        }
        return hasNext;
      });
    });
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (Exception e) {
      throw ExceptionUtil.unwrapThrowable(e);
    }
  }

  private static int width(String text) {
    return text.codePoints().map(cp -> WIDE_SCRIPTS.contains(Character.UnicodeScript.of(cp)) ? 2 : 1).sum();
  }

  private static class ResultTable {

    // ponytail: 건수 제한 없음. 수십만 건 조회 시 결과 전체가 문자열로 메모리에 올라감
    private final List<String[]> rows = new ArrayList<>();
    private String[] columns;
    private int[] types;
    // 첫 LONG 컬럼 위치. 이 위치부터는 읽지 않음
    private int longAt;

    void capture(ResultSet rs, boolean hasRow) throws SQLException {
      if (columns == null) {
        ResultSetMetaData metaData = rs.getMetaData();
        columns = new String[metaData.getColumnCount()];
        types = new int[columns.length];
        longAt = columns.length;
        for (int i = 0; i < columns.length; i++) {
          columns[i] = metaData.getColumnLabel(i + 1);
          types[i] = metaData.getColumnType(i + 1);
          if (longAt > i && LONG_TYPES.contains(types[i])) {
            longAt = i;
          }
        }
      }
      if (!hasRow) {
        return;
      }
      String[] row = new String[columns.length];
      for (int i = 0; i < row.length; i++) {
        row[i] = i >= longAt ? "<<LONG>>" : read(rs, i);
      }
      rows.add(row);
    }

    private String read(ResultSet rs, int index) {
      if (BINARY_TYPES.contains(types[index])) {
        return "<<BINARY>>";
      }
      try {
        String value = rs.getString(index + 1);
        return value == null ? "[null]" : value.replaceAll("[\\r\\n\\t]", " ");
      } catch (SQLException e) {
        return "<<Cannot Display>>";
      }
    }

    String render() {
      if (columns == null) {
        return "";
      }
      int[] widths = new int[columns.length];
      for (int i = 0; i < columns.length; i++) {
        widths[i] = width(columns[i]);
        for (String[] row : rows) {
          widths[i] = Math.max(widths[i], width(row[i]));
        }
      }
      String border = Arrays.stream(widths).mapToObj(w -> "-".repeat(w + 2)).collect(Collectors.joining("+", "+", "+"));

      StringBuilder out = new StringBuilder("\n").append(border).append('\n').append(line(columns, widths)).append('\n').append(border);
      for (String[] row : rows) {
        out.append('\n').append(line(row, widths));
      }
      if (!rows.isEmpty()) {
        out.append('\n').append(border);
      }
      return out.append("\n(").append(rows.size()).append(" rows)").toString();
    }

    private static String line(String[] cells, int[] widths) {
      StringBuilder out = new StringBuilder("|");
      for (int i = 0; i < cells.length; i++) {
        out.append(' ').append(cells[i]).append(" ".repeat(widths[i] - width(cells[i]))).append(" |");
      }
      return out.toString();
    }
  }
}
