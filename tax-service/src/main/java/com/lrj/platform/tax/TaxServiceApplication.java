package com.lrj.platform.tax;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 财税发票风险审查服务。 */
@SpringBootApplication
@EnableConfigurationProperties(TaxReviewProperties.class)
public class TaxServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaxServiceApplication.class, args);
    }
}
