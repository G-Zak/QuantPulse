package com.quantpulse.api.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Users, kept in memory and loaded from config.
 *
 * Good enough for this project. Moving to a database only means changing this class.
 * Passwords are still hashed with BCrypt at startup.
 */
@Service
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    public record Account(String username, String passwordHash, List<String> roles) {
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final PasswordEncoder encoder;
    private final String demoUser;
    private final String demoPassword;

    public UserAccountService(PasswordEncoder encoder,
                              @Value("${quantpulse.security.demo-user:zakaria}") String demoUser,
                              @Value("${quantpulse.security.demo-password:quantpulse}") String demoPassword) {
        this.encoder = encoder;
        this.demoUser = demoUser;
        this.demoPassword = demoPassword;
    }

    @PostConstruct
    void seed() {
        register(demoUser, demoPassword, List.of("USER", "ADMIN"));
        log.info("[AUTH] seeded account '{}' — change quantpulse.security.demo-password "
                + "before this is reachable from anywhere but localhost", demoUser);
    }

    public void register(String username, String rawPassword, List<String> roles) {
        accounts.put(username, new Account(username, encoder.encode(rawPassword), roles));
    }

    /**
     * Checks a username and password.
     * Returns empty for both unknown user and wrong password, so nobody can guess which usernames exist.
     */
    public Optional<Account> authenticate(String username, String rawPassword) {
        Account account = accounts.get(username);
        if (account == null) {
            // Hash anyway, so an unknown user takes as long as a wrong password.
            encoder.encode(rawPassword);
            return Optional.empty();
        }
        return encoder.matches(rawPassword, account.passwordHash())
                ? Optional.of(account)
                : Optional.empty();
    }
}
