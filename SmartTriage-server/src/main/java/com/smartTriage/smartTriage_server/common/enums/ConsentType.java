package com.smartTriage.smartTriage_server.common.enums;

/**
 * The kind of intervention an informed-consent record covers. Drives which
 * disclosures are clinically expected (e.g. anaesthesia and transfusion carry
 * distinct risk profiles) and lets the chart group consents by purpose.
 */
public enum ConsentType {
    PROCEDURE,
    SURGERY,
    ANAESTHESIA,
    BLOOD_TRANSFUSION,
    HIV_TEST,
    SEDATION,
    IMAGING_CONTRAST,
    RESEARCH_PARTICIPATION,
    PHOTOGRAPHY,
    OTHER
}
