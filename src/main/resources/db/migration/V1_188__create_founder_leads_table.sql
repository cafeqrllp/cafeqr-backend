-- V1.188: Create Founder Leads and Lead Activities tables for Sales CRM

CREATE TABLE IF NOT EXISTS founder_leads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    business_name VARCHAR(255),
    
    -- Omni-channel contact numbers
    primary_phone VARCHAR(50) NOT NULL,
    whatsapp_number VARCHAR(50),
    alternate_phone VARCHAR(50),
    email VARCHAR(255),
    city VARCHAR(100),
    
    source VARCHAR(50) NOT NULL DEFAULT 'PHONE_CALL', -- WHATSAPP, INSTAGRAM, FACEBOOK, PHONE_CALL, WEBSITE, WALK_IN, REFERRAL
    status VARCHAR(50) NOT NULL DEFAULT 'YET_TO_REACH_OUT', -- YET_TO_REACH_OUT, NEW_INQUIRY, IN_DISCUSSION, DEMO_SCHEDULED, ONBOARDED_TRIAL, PAID_ACTIVE, COLD_FOLLOW_UP, LOST
    temperature VARCHAR(20) DEFAULT 'WARM', -- HOT, WARM, COLD
    deal_value_estimate_paise BIGINT DEFAULT 0,
    
    -- Dual Linkage: Direct Branch link with Client Fallback
    assigned_org_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
    assigned_client_id UUID REFERENCES clients(id) ON DELETE SET NULL,
    
    -- Scheduling & Reminders (Google Calendar replacement)
    next_follow_up_at TIMESTAMP,
    follow_up_note VARCHAR(500),
    last_contacted_at TIMESTAMP,
    
    notes TEXT,
    lost_reason VARCHAR(255),
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_founder_leads_primary_phone ON founder_leads(primary_phone);
CREATE INDEX IF NOT EXISTS idx_founder_leads_whatsapp ON founder_leads(whatsapp_number);
CREATE INDEX IF NOT EXISTS idx_founder_leads_alt_phone ON founder_leads(alternate_phone);
CREATE INDEX IF NOT EXISTS idx_founder_leads_status ON founder_leads(status);
CREATE INDEX IF NOT EXISTS idx_founder_leads_follow_up ON founder_leads(next_follow_up_at);
CREATE INDEX IF NOT EXISTS idx_founder_leads_client_id ON founder_leads(assigned_client_id);
CREATE INDEX IF NOT EXISTS idx_founder_leads_org_id ON founder_leads(assigned_org_id);

CREATE TABLE IF NOT EXISTS founder_lead_activities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lead_id UUID NOT NULL REFERENCES founder_leads(id) ON DELETE CASCADE,
    activity_type VARCHAR(50) NOT NULL, -- CALL, WHATSAPP, DEMO, NOTE, STATUS_CHANGE, PAYMENT_RECORDED
    description TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_founder_lead_activities_lead ON founder_lead_activities(lead_id);
