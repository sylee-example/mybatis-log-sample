package com.example.mybatislog.user;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

  List<User> findUsers(@Param("name") String name, @Param("email") String email);

  int insert(User user);
}
