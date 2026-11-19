package com.banking.userservice.service;

import com.banking.userservice.dto.AddressDto;
import com.banking.userservice.dto.CreateCustomerRequest;
import com.banking.userservice.dto.CustomerResponse;
import com.banking.userservice.entity.Customer;
import com.banking.userservice.exception.DuplicateResourceException;
import com.banking.userservice.exception.ResourceNotFoundException;
import com.banking.userservice.mapper.CustomerMapper;
import com.banking.userservice.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test: no Spring context, no database. The repository is mocked, so only
 * the service's own logic is tested. Runs in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(customerRepository, new CustomerMapper());
    }

    @Test
    void create_normalizesEmailAndSaves() {
        when(customerRepository.existsByKeycloakUserId("kc-1")).thenReturn(false);
        when(customerRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerResponse response = customerService.create(request("kc-1", "  Jane@Example.COM "));

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("jane@example.com");
        assertThat(response.keycloakUserId()).isEqualTo("kc-1");
    }

    @Test
    void create_rejectsSecondProfileForSameIdentity() {
        when(customerRepository.existsByKeycloakUserId("kc-1")).thenReturn(true);

        assertThatThrownBy(() -> customerService.create(request("kc-1", "jane@example.com")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("kc-1");
        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_rejectsDuplicateEmail() {
        when(customerRepository.existsByKeycloakUserId("kc-2")).thenReturn(false);
        when(customerRepository.existsByEmail("jane@example.com")).thenReturn(true);

        assertThatThrownBy(() -> customerService.create(request("kc-2", "jane@example.com")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void getById_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    private static CreateCustomerRequest request(String keycloakUserId, String email) {
        return new CreateCustomerRequest(keycloakUserId, "Jane", "Doe", email, "+66 81 234 5678",
                new AddressDto("1 Silom Rd", "Bangkok", "10500", "TH"));
    }
}
