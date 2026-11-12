-- One researcher's outside notification preferences, one row per account: whether each channel
-- (email, Telegram) is on, the Telegram chat id, and each channel's thresholds. A threshold is low,
-- medium, high or null (never). Accounts made before this migration get the default row below; the
-- app writes one at registration. The dev mode admin's row goes with its account.
create table notification_preferences (
    user_id                      uuid    primary key,
    email_enabled                boolean not null,
    email_min_alert_severity     text,
    email_min_change_severity    text,
    email_min_impact_level       text,
    telegram_enabled             boolean not null,
    telegram_chat_id             bigint,
    telegram_min_alert_severity  text,
    telegram_min_change_severity text,
    telegram_min_impact_level    text,
    constraint fk_notification_preferences_user foreign key (user_id) references users (id) on delete cascade
);

insert into notification_preferences (
    user_id, email_enabled, email_min_alert_severity, email_min_change_severity, email_min_impact_level,
    telegram_enabled, telegram_min_alert_severity, telegram_min_change_severity, telegram_min_impact_level)
select id, false, 'high', 'medium', 'medium', false, 'high', 'medium', 'medium' from users;
