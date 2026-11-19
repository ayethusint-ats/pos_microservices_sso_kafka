package com.banking.userservice.mapper;

import com.banking.userservice.dto.AddressDto;
import com.banking.userservice.dto.CustomerResponse;
import com.banking.userservice.entity.Address;
import com.banking.userservice.entity.Customer;
import org.springframework.stereotype.Component;

/**
 * Converts between entities and DTOs. Hand-written on purpose so the mapping is visible.
 * Libraries like MapStruct generate the same code at compile time.
 */
@Component
public class CustomerMapper {

    public CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getKeycloakUserId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhoneNumber(),
                toDto(customer.getAddress()),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }

    public Address toEntity(AddressDto dto) {
        return new Address(dto.street(), dto.city(), dto.postalCode(), dto.country());
    }

    private AddressDto toDto(Address address) {
        if (address == null) {
            return null;
        }
        return new AddressDto(address.getStreet(), address.getCity(),
                address.getPostalCode(), address.getCountry());
    }
}
