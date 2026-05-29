package org.example.authorizationservice.service;

import lombok.RequiredArgsConstructor;
import org.example.authorizationservice.model.UserEntity;
import org.example.authorizationservice.repository.UserRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthorizationUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    //FIXME: Don't forget to set the authorities!!!!

    @Override
    public UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
        UserEntity userEntity = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("UserEntity not found: " + username));

        return User.withUsername(userEntity.getUsername())
                .password(userEntity.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .disabled(!userEntity.isEnabled())
                .accountLocked(!userEntity.isAccountNonLocked())
                .accountExpired(!userEntity.isAccountNonExpired())
                .credentialsExpired(!userEntity.isCredentialsNonExpired())
                .build();
    }
}
