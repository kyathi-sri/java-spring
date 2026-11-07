package com.securebank.transfer.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the HTTP clients with controllable fakes for saga tests. */
@TestConfiguration
public class FakeDownstreams {

    @Bean
    @Primary
    public FakeAccounts fakeAccounts() {
        return new FakeAccounts();
    }

    @Bean
    @Primary
    public FakeLedger fakeLedger() {
        return new FakeLedger();
    }
}
