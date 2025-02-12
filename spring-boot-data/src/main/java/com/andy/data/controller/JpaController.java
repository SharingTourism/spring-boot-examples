package com.andy.data.controller;

import com.andy.data.entity.User;
import com.andy.data.repository.jpa.UserRepository;
import com.andy.data.service.JpaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * @author Leone
 * @since 2018-05-11
 **/
@Slf4j
@RestController
@RequestMapping("/api/jpa")
public class JpaController {

    @Autowired
    private JpaService jpaService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/batchInsert")
    public String insertBatch(@RequestParam(required = false, defaultValue = "1") Integer count) {
        long time = jpaService.insertBatch(count);
        return "batch insert " + count + " expenditure:" + time + " ms!";
    }

    @GetMapping("/foreachInsert")
    public String insertForeach(@RequestParam(required = false, defaultValue = "1") Integer count) {
        long time = jpaService.insertForeach(count);
        return "foreach insert " + count + " expenditure:" + time + " ms!";
    }

    @PutMapping("/update")
    public String update(@RequestBody User user) {
        int result = jpaService.update(user);
        return "update one expenditure:" + result + " ms!";
    }

}
