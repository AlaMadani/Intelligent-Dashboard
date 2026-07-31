package com.neo.dashboard.entity;

/**
 * Defines the access control roles available in the dashboard application.
 * Each role grants a specific level of system permissions, from full
 * administrative access to read-only viewing capabilities.
 */
public enum UserRole {
    /** Full system access including user management and configuration. */
    ADMIN,
    /** Access to analytics dashboards and report generation features. */
    ANALYST,
    /** Read-only access to view dashboards and pre-generated reports. */
    VIEWER
}
