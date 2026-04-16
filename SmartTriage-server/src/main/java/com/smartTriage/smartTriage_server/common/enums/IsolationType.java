package com.smartTriage.smartTriage_server.common.enums;

/**
 * Types of infection isolation precautions.
 * Determines PPE requirements and room assignment.
 */
public enum IsolationType {
    /** TB, measles, chickenpox — negative pressure room */
    AIRBORNE,
    /** Influenza, meningococcal, COVID-19 */
    DROPLET,
    /** MRSA, C. diff, viral gastroenteritis */
    CONTACT,
    /** Ebola, Marburg (critical in Rwanda context) */
    STRICT,
    /** Immunocompromised patient protection */
    PROTECTIVE
}
