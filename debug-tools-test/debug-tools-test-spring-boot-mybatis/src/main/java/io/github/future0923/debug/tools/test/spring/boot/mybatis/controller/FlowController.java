package io.github.future0923.debug.tools.test.spring.boot.mybatis.controller;

import io.github.future0923.debug.tools.test.spring.boot.mybatis.annotations.RestController_V1;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * @author future0923
 */
@RestController_V1("flow")
public class FlowController {

    @GetMapping("{flowId}/jobs")
    public List<String> list(@PathVariable String flowId) {
        return null;
    }
}
