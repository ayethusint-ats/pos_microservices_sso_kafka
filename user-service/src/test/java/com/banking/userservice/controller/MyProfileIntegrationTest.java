package com.banking.userservice.controller;

import com.banking.userservice.TestcontainersConfiguration;
import com.banking.userservice.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.banking.userservice.support.TestUsers.ALICE_ID;
import static com.banking.userservice.support.TestUsers.BOB_ID;
import static com.banking.userservice.support.TestUsers.ada;
import static com.banking.userservice.support.TestUsers.alice;
import static com.banking.userservice.support.TestUsers.bob;
import static com.banking.userservice.support.TestUsers.sam;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/customers/me: each customer manages exactly one profile, their own. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MyProfileIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CustomerRepository customerRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        customerRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void customerWithoutProfileGets404ThenCreatesIt() throws Exception {
        mockMvc.perform(get("/api/customers/me").with(alice())).andExpect(status().isNotFound());

        createProfile(alice(), "alice@example.com")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/customers/me"))
                .andExpect(jsonPath("$.keycloakUserId").value(ALICE_ID));   // identity from the token

        mockMvc.perform(get("/api/customers/me").with(alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void identityInTheBodyIsIgnored() throws Exception {
        // Alice tries to create a profile "for bob" by adding keycloakUserId to the JSON.
        mockMvc.perform(post("/api/customers/me").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("alice@example.com").replace("{", "{\"keycloakUserId\":\"" + BOB_ID + "\",")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keycloakUserId").value(ALICE_ID));
    }

    @Test
    void eachCustomerOnlySeesAndEditsTheirOwnProfile() throws Exception {
        createProfile(alice(), "alice@example.com").andExpect(status().isCreated());
        createProfile(bob(), "bob@example.com").andExpect(status().isCreated());

        mockMvc.perform(put("/api/customers/me").with(bob()).contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("bob.new@example.com")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/customers/me").with(alice()))
                .andExpect(jsonPath("$.email").value("alice@example.com"));     // untouched
    }

    @Test
    void secondProfileForSameIdentityIs409() throws Exception {
        createProfile(alice(), "alice@example.com").andExpect(status().isCreated());
        createProfile(alice(), "alice2@example.com").andExpect(status().isConflict());
    }

    @Test
    void staffHaveNoSelfServiceProfile() throws Exception {
        for (RequestPostProcessor staff : new RequestPostProcessor[]{sam(), ada()}) {
            mockMvc.perform(get("/api/customers/me").with(staff))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Forbidden"));
        }
    }

    @Test
    void invalidProfileIs400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/customers/me").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"\",\"lastName\":\"Doe\",\"email\":\"nope\","
                                + "\"address\":{\"street\":\"x\",\"city\":\"y\",\"postalCode\":\"1\",\"country\":\"Thailand\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.firstName").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors['address.country']").exists());
    }

    private ResultActions createProfile(RequestPostProcessor user, String email) throws Exception {
        return mockMvc.perform(post("/api/customers/me").with(user).contentType(MediaType.APPLICATION_JSON)
                .content(profileJson(email)));
    }

    static String profileJson(String email) {
        return """
                {"firstName": "Test", "lastName": "Customer", "email": "%s", "phoneNumber": "+66 81 234 5678",
                 "address": {"street": "1 Silom Rd", "city": "Bangkok", "postalCode": "10500", "country": "TH"}}
                """.formatted(email);
    }
}
