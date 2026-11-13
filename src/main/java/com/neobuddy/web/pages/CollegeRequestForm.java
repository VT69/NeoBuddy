package com.neobuddy.web.pages;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The "request your college" form.
 *
 * @param emailDomain the domain of the student's college email, e.g. {@code college.edu.in}
 * @param contactEmail optional: where to tell them when their college is on NeoBuddy
 */
record CollegeRequestForm(
    @NotBlank(message = "Enter your college's name.")
        @Size(max = 120, message = "Keep the college name under 120 characters.")
        String collegeName,
    @NotBlank(message = "Enter your college's email domain.")
        @Size(max = 100, message = "That domain is too long.")
        @Pattern(
            regexp =
                "\\s*@?[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+\\s*",
            message = "Enter a domain such as college.edu.in.")
        String emailDomain,
    @Email(message = "Enter a valid email address, or leave it empty.")
        @Size(max = 254, message = "That address is too long.")
        String contactEmail) {}
