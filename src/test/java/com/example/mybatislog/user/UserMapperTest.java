package com.example.mybatislog.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mybatislog.config.SqlLogInterceptor;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

// Oracle 대신 H2 Oracle 모드 사용. 시드 데이터: src/test/resources/data.sql
@MybatisTest(properties = "spring.datasource.url=jdbc:h2:mem:testdb;MODE=Oracle")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SqlLogInterceptor.class)
@ExtendWith(OutputCaptureExtension.class)
class UserMapperTest {

  @Autowired
  private UserMapper userMapper;

  @Test
  void logsMultiLineSqlWithBoundParamsAndResultTable(CapturedOutput output) {
    userMapper.findUsers("김", null);

    // 한글은 2칸으로 계산되어 표가 정렬되어야 함
    assertThat(output).contains("""
        SELECT id
            , name
            , email
            , created_at
         FROM users
        WHERE  name LIKE '%' || '김' || '%'
        ORDER BY id
        +----+--------+-----------------+---------------------+
        | ID | NAME   | EMAIL           | CREATED_AT          |
        +----+--------+-----------------+---------------------+
        | 1  | 김철수 | kim@example.com | 2026-10-01 09:00:00 |
        +----+--------+-----------------+---------------------+
        (1 rows)""");
  }

  @Test
  void logsNullValueAndEmptyResult(CapturedOutput output) {
    userMapper.findUsers(null, null);
    userMapper.findUsers("없는사람", null);

    assertThat(output).contains("| 2  | Lee    | [null]          | 2026-10-02 18:30:00 |", "(2 rows)");
    assertThat(output).contains("""
        | ID | NAME | EMAIL | CREATED_AT |
        +----+------+-------+------------+
        (0 rows)""");
  }

  @Test
  void logsUpdateWithOracleLiteralsAndAffectedRows(CapturedOutput output) {
    userMapper.insert(new User(null, "O'Brien", null, LocalDateTime.of(2026, 10, 5, 21, 42)));

    assertThat(output).contains("""
        INSERT INTO users (id, name, email, created_at)
        VALUES (users_seq.NEXTVAL, 'O''Brien', NULL, TIMESTAMP '2026-10-05 21:42:00.0')
        affected rows: 1""");
  }

  @Test
  void logsAllRowsWithoutLimit(CapturedOutput output) {
    for (int i = 0; i < 150; i++) {
      userMapper.insert(new User(null, "user" + i, null, null));
    }

    userMapper.findUsers("user", null);

    assertThat(output).contains("| user149 |", "(150 rows)");
  }
}
