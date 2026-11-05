package com.moagwa.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class DevDataInitializerTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final AdminRepository admins = mock(AdminRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final DevDataInitializer initializer = new DevDataInitializer(organizations, admins, encoder);

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("hash");
        when(admins.findByLoginId(anyString())).thenReturn(Optional.empty());
        when(organizations.findByName(anyString())).thenReturn(Optional.empty());
        when(organizations.save(any(Organization.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createsAdminsForOrganizationsOtherSeedDataAlreadyCreated() {
        Organization existing = new Organization("총학생회", OrganizationType.STUDENT_GOVERNMENT);
        when(organizations.findByName("총학생회")).thenReturn(Optional.of(existing));

        initializer.run();

        ArgumentCaptor<Admin> saved = ArgumentCaptor.forClass(Admin.class);
        verify(admins, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Admin::getLoginId)
                .containsExactly("student-council", "ai-council", "cs-council");
        assertThat(saved.getAllValues().get(0).getOrganization()).isSameAs(existing);
    }

    @Test
    void leavesExistingAdminsAlone() {
        when(admins.findByLoginId(anyString())).thenReturn(Optional.of(mock(Admin.class)));

        initializer.run();

        verify(admins, never()).save(any());
        verify(organizations, never()).save(any());
    }
}
