package com.chutneytesting.junit.api;

import com.chutneytesting.environment.domain.Environment;
import com.chutneytesting.environment.domain.Target;

public interface EnvironmentService {

    void addEnvironment(Environment environment);

    void deleteEnvironment(String environmentName);

    void addTarget(String environmentName, Target target);
}
