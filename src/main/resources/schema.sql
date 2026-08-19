create table if not exists creators (
  id bigserial primary key,
  handle varchar(40) unique not null,
  display_name varchar(80) not null,
  email varchar(255) unique not null,
  phone varchar(32),
  password_hash varchar(255) not null,
  bio varchar(280) not null default '',
  avatar_url varchar(2048),
  created_at timestamptz not null default current_timestamp
);

create table if not exists stores (
  id bigserial primary key,
  creator_id bigint unique not null references creators(id) on delete cascade,
  title varchar(100) not null,
  theme varchar(40) not null default 'violet',
  currency char(3) not null default 'INR',
  published boolean not null default true,
  payouts_enabled boolean not null default false
);

create table if not exists links (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  title varchar(100) not null,
  url varchar(2048) not null,
  position integer not null default 0,
  published boolean not null default true
);

create table if not exists products (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  type varchar(40) not null default 'digital-download',
  title varchar(100) not null,
  description varchar(2000) not null,
  price_cents integer not null check(price_cents >= 0),
  status varchar(20) not null default 'published',
  position integer not null default 0,
  thumbnail_url varchar(2048),
  fulfillment_url varchar(2048),
  created_at timestamptz not null default current_timestamp
);

alter table products add column if not exists type varchar(40) not null default 'digital-download';
alter table products add column if not exists status varchar(20) not null default 'published';
alter table products add column if not exists position integer not null default 0;
alter table products add column if not exists thumbnail_url varchar(2048);
alter table products add column if not exists fulfillment_url varchar(2048);
alter table products add column if not exists created_at timestamptz not null default current_timestamp;

create table if not exists product_payment_plans (
  id bigserial primary key,
  product_id bigint not null references products(id) on delete cascade,
  name varchar(80) not null,
  amount_cents integer not null,
  interval_name varchar(20) not null default 'one_time',
  interval_count integer not null default 1
);

create table if not exists product_files (
  id bigserial primary key,
  product_id bigint not null references products(id) on delete cascade,
  file_name varchar(255) not null,
  object_key varchar(512) not null
);

create table if not exists product_checkout_fields (
  id bigserial primary key,
  product_id bigint not null references products(id) on delete cascade,
  label varchar(120) not null,
  field_type varchar(30) not null,
  required boolean not null default false,
  position integer not null default 0
);

create table if not exists product_reviews (
  id bigserial primary key,
  product_id bigint not null references products(id) on delete cascade,
  author_name varchar(80) not null,
  body varchar(1000) not null,
  rating integer check (rating between 1 and 5),
  approved boolean not null default false
);

create table if not exists customers (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  name varchar(120) not null,
  email varchar(255) not null,
  phone varchar(32),
  source varchar(40) not null default 'checkout',
  created_at timestamptz not null default current_timestamp,
  unique(creator_id,email)
);

create table if not exists tags (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  name varchar(60) not null,
  unique(creator_id,name)
);

create table if not exists customer_tags (
  customer_id bigint not null references customers(id) on delete cascade,
  tag_id bigint not null references tags(id) on delete cascade,
  primary key(customer_id,tag_id)
);

create table if not exists orders (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  customer_id bigint references customers(id) on delete set null,
  product_id bigint references products(id) on delete set null,
  amount_cents integer not null,
  fee_cents integer not null default 0,
  status varchar(30) not null default 'paid',
  external_payment_id varchar(255),
  created_at timestamptz not null default current_timestamp
);

create table if not exists store_visits (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  path varchar(512) not null,
  referrer varchar(512),
  occurred_at timestamptz not null default current_timestamp
);

create table if not exists leads (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  product_id bigint references products(id) on delete set null,
  email varchar(255) not null,
  created_at timestamptz not null default current_timestamp
);

create table if not exists click_events (
  id bigserial primary key,
  link_id bigint not null references links(id) on delete cascade,
  occurred_at timestamptz not null default current_timestamp,
  referrer varchar(500)
);

create table if not exists integrations (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  provider varchar(60) not null,
  status varchar(30) not null default 'disconnected',
  external_account_label varchar(120),
  unique(creator_id,provider)
);

create table if not exists notification_preferences (
  creator_id bigint primary key references creators(id) on delete cascade,
  order_emails boolean not null default true,
  marketing_emails boolean not null default true,
  payout_emails boolean not null default true
);

create table if not exists automations (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  name varchar(100) not null,
  trigger_type varchar(40) not null,
  message varchar(975) not null,
  status varchar(20) not null default 'draft',
  created_at timestamptz not null default current_timestamp
);

create table if not exists automation_keywords (
  automation_id bigint not null references automations(id) on delete cascade,
  keyword varchar(80) not null,
  primary key(automation_id,keyword)
);

create table if not exists automation_stats (
  automation_id bigint primary key references automations(id) on delete cascade,
  comments_seen bigint not null default 0,
  messages_sent bigint not null default 0,
  link_clicks bigint not null default 0,
  updated_at timestamptz not null default current_timestamp
);

create table if not exists funnels (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  name varchar(100) not null,
  status varchar(20) not null default 'draft'
);

create table if not exists funnel_steps (
  id bigserial primary key,
  funnel_id bigint not null references funnels(id) on delete cascade,
  product_id bigint references products(id) on delete set null,
  step_type varchar(30) not null,
  position integer not null
);

create table if not exists availability_schedules (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  name varchar(100) not null,
  timezone varchar(80) not null
);

create table if not exists bookings (
  id bigserial primary key,
  schedule_id bigint not null references availability_schedules(id) on delete cascade,
  customer_id bigint references customers(id) on delete set null,
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  status varchar(20) not null default 'confirmed'
);

create index if not exists idx_products_creator_status on products(creator_id,status,position);
create index if not exists idx_orders_creator_created on orders(creator_id,created_at desc);
create index if not exists idx_visits_creator_time on store_visits(creator_id,occurred_at desc);
create index if not exists idx_customers_creator_created on customers(creator_id,created_at desc);

-- Existing column names are retained for backward-compatible local volumes. They hold
-- generic smallest-currency-unit values (paise for INR), never decimal rupee values.
comment on column products.price_cents is 'Deprecated name: smallest currency units; paise when store currency is INR';
comment on column orders.amount_cents is 'Deprecated name: smallest currency units; paise when store currency is INR';
comment on column orders.fee_cents is 'Deprecated name: smallest currency units; paise when store currency is INR';

create table if not exists checkout_sessions (
  id varchar(36) primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  product_id bigint not null references products(id) on delete restrict,
  provider varchar(20) not null,
  provider_session_id varchar(255),
  idempotency_key varchar(120) not null,
  currency char(3) not null,
  amount_subunits integer not null check(amount_subunits >= 0),
  status varchar(30) not null default 'created',
  created_at timestamptz not null default current_timestamp,
  updated_at timestamptz not null default current_timestamp,
  unique(creator_id,idempotency_key)
);

create table if not exists provider_events (
  id bigserial primary key,
  provider varchar(20) not null,
  event_key varchar(64) not null,
  event_type varchar(100) not null,
  payload_sha256 varchar(64) not null,
  signature_verified boolean not null,
  processed_at timestamptz not null default current_timestamp,
  unique(provider,event_key)
);

create table if not exists instagram_events (
  id bigserial primary key,
  event_key varchar(64) unique not null,
  payload_sha256 varchar(64) not null,
  signature_verified boolean not null,
  processed_at timestamptz not null default current_timestamp
);

update stores set currency='INR'
where currency='USD' and creator_id in (select id from creators where email like '%@example.test');
update stores set payouts_enabled=false where creator_id in (select id from creators where email like '%@example.test');
update products set price_cents=49900 where title='Creator Content Calendar' and creator_id in (select id from creators where email like '%@example.test');
update products set price_cents=249900 where title='Strategy Session' and creator_id in (select id from creators where email like '%@example.test');
update orders set amount_cents=49900,fee_cents=998 where creator_id in (select id from creators where email like '%@example.test');

create index if not exists idx_checkout_creator_created on checkout_sessions(creator_id,created_at desc);

alter table checkout_sessions add column if not exists buyer_email varchar(255);
alter table checkout_sessions add column if not exists buyer_name varchar(120);
alter table checkout_sessions add column if not exists order_id bigint references orders(id);

create table if not exists entitlements (
  id bigserial primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  product_id bigint not null references products(id) on delete cascade,
  order_id bigint not null references orders(id) on delete cascade,
  customer_id bigint references customers(id) on delete set null,
  access_token varchar(64) unique not null,
  status varchar(20) not null default 'active',
  granted_at timestamptz not null default current_timestamp
);

create index if not exists idx_entitlements_token on entitlements(access_token);

alter table checkout_sessions add column if not exists field_responses text;

create table if not exists order_field_responses (
  id bigserial primary key,
  order_id bigint not null references orders(id) on delete cascade,
  field_id bigint not null references product_checkout_fields(id) on delete cascade,
  value varchar(2000) not null
);

alter table bookings add column if not exists product_id bigint references products(id);
alter table checkout_sessions add column if not exists slot_id bigint references bookings(id);

create table if not exists webinar_sessions (
  id bigserial primary key,
  product_id bigint not null references products(id) on delete cascade,
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  join_url varchar(2048) not null,
  capacity integer not null default 0
);

create table if not exists webinar_registrations (
  id bigserial primary key,
  session_id bigint not null references webinar_sessions(id) on delete cascade,
  customer_id bigint references customers(id) on delete set null,
  order_id bigint not null references orders(id) on delete cascade
);

alter table checkout_sessions add column if not exists plan_id bigint references product_payment_plans(id);

create table if not exists membership_subscriptions (
  id bigserial primary key,
  product_id bigint not null references products(id) on delete cascade,
  customer_id bigint references customers(id) on delete set null,
  order_id bigint not null references orders(id) on delete cascade,
  plan_id bigint not null references product_payment_plans(id),
  current_period_end timestamptz not null,
  status varchar(20) not null default 'active'
);

create table if not exists course_modules (
  id bigserial primary key,
  product_id bigint not null references products(id) on delete cascade,
  title varchar(120) not null,
  position integer not null default 0
);

create table if not exists course_lessons (
  id bigserial primary key,
  module_id bigint not null references course_modules(id) on delete cascade,
  title varchar(120) not null,
  video_url varchar(2048),
  content varchar(4000),
  position integer not null default 0
);

create table if not exists course_enrollments (
  id bigserial primary key,
  product_id bigint not null references products(id) on delete cascade,
  customer_id bigint references customers(id) on delete set null,
  order_id bigint not null references orders(id) on delete cascade
);

create table if not exists lesson_progress (
  id bigserial primary key,
  enrollment_id bigint not null references course_enrollments(id) on delete cascade,
  lesson_id bigint not null references course_lessons(id) on delete cascade,
  completed_at timestamptz not null default current_timestamp,
  unique(enrollment_id,lesson_id)
);

create table if not exists sessions (
  id varchar(64) primary key,
  creator_id bigint not null references creators(id) on delete cascade,
  created_at timestamptz not null default current_timestamp,
  expires_at timestamptz not null,
  revoked_at timestamptz
);

create index if not exists idx_sessions_creator on sessions(creator_id);
