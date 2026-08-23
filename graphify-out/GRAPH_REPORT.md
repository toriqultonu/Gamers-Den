# Graph Report - main  (2026-08-22)

## Corpus Check
- 270 files · ~53,185 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2373 nodes · 5307 edges · 225 communities (145 shown, 80 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 543 edges (avg confidence: 0.79)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Error Code Taxonomy
- Bill Assembly & Ports
- Station Floor & Pricing
- Cart & Stock Domain
- Bill Arithmetic Rules
- Web Request/Response DTOs
- Session Domain Core
- Idempotency Replay Guard
- Station Lookup Ports
- Bean Validation Constraints
- Auth Domain Services
- Payment & Settlement
- Member Domain Core
- Filter Chain & Tracing
- Member Settlement Ports
- Print Job & Alert Queue
- Shift Report Computation
- Shift Service & Repos
- Terminal Settings
- Validation Failure Paths
- Cart Repositories
- Session Clock & State
- Session Lifecycle Concepts
- Staff & Member Web Views
- Money & Charges
- Receipt Printing Ports
- Session Lookup Services
- Spring Bootstrap Config
- Transaction Ledger
- Print Job Rendering
- Session Settlement
- Database Schema (Flyway)
- SPI Port Families
- Security Configuration
- JWT & Refresh Tokens
- Settlement Ports
- Catalog Web Layer
- Cash Count & Expenses
- Item & Stock Movement
- External Framework Types
- Takings Lookup Port
- Staff Service
- Transaction Repositories
- Item Web DTOs
- Wallet & Points Ledger
- Auth Properties & Beans
- Cart Line Domain
- Member Registration
- Item Domain
- Receipt Renderer
- Expense Recording
- Alert Domain
- Member Ledger Repos
- Points Ledger Repos
- Station Lookup & Floor
- Shift Lookup Ports
- Auth Error Handling
- Session Web DTOs
- Shift Web DTOs
- Bounded Context Overviews
- Payment Web DTOs
- Session Repositories
- Prepaid Seat & Token Queue
- Session Bill Lookup
- Pro-Rata Cash Attribution
- Schema Constraints & Indexes
- Staff Security Principal
- Session Visit Lookup
- Print Job Types
- Sync Outbox
- Item Stock Mutations
- Shift Service Core
- Station Floor State
- Idempotency Store
- Member Web Layer
- Session Balance
- Station Web Views
- Concepts & Rationale 77
- Concepts & Rationale 78
- Concepts & Rationale 79
- Concepts & Rationale 80
- Concepts & Rationale 81
- Idempotency 82
- Shift Repos 83
- Cross-Context Ports 84
- Station Domain 85
- Concepts & Rationale 86
- Concepts & Rationale 87
- Concepts & Rationale 88
- Concepts & Rationale 89
- Auth Domain 90
- Session Domain 91
- Concepts & Rationale 92
- Concepts & Rationale 93
- Concepts & Rationale 94
- Concepts & Rationale 95
- Concepts & Rationale 96
- Concepts & Rationale 97
- Concepts & Rationale 98
- Auth Repos 99
- Printing Domain 100
- Token Queue 101
- Concepts & Rationale 102
- Concepts & Rationale 103
- Concepts & Rationale 104
- Concepts & Rationale 105
- Concepts & Rationale 106
- Concepts & Rationale 107
- Concepts & Rationale 108
- Concepts & Rationale 109
- Concepts & Rationale 110
- Concepts & Rationale 111
- Concepts & Rationale 112
- Concepts & Rationale 113
- Tournament (stub) 114
- Member Domain 115
- Shift Domain 116
- Concepts & Rationale 117
- Concepts & Rationale 118
- Concepts & Rationale 119
- Concepts & Rationale 120
- Concepts & Rationale 121
- Concepts & Rationale 122
- Concepts & Rationale 123
- Concepts & Rationale 124
- App Configuration 125
- Concepts & Rationale 126
- Request Tracing 127
- Member Domain 128
- Concepts & Rationale 129
- Concepts & Rationale 130
- Concepts & Rationale 131
- Concepts & Rationale 132
- Concepts & Rationale 133
- Concepts & Rationale 134
- Concepts & Rationale 135
- Concepts & Rationale 136
- Concepts & Rationale 137
- Concepts & Rationale 138
- Billing Domain 139
- Error Handling 140
- Concepts & Rationale 141
- Concepts & Rationale 142
- Concepts & Rationale 143
- Concepts & Rationale 144
- Concepts & Rationale 145
- Concepts & Rationale 146
- Concepts & Rationale 147
- Concepts & Rationale 148
- Concepts & Rationale 149
- Concepts & Rationale 150
- Concepts & Rationale 151
- Concepts & Rationale 152
- Concepts & Rationale 153
- Concepts & Rationale 154
- Concepts & Rationale 155
- Concepts & Rationale 156
- Concepts & Rationale 157
- Concepts & Rationale 158
- Concepts & Rationale 159
- Concepts & Rationale 160
- Concepts & Rationale 161
- Concepts & Rationale 163
- Concepts & Rationale 164
- Concepts & Rationale 165
- Concepts & Rationale 166
- Concepts & Rationale 167
- Concepts & Rationale 168
- Concepts & Rationale 169
- Concepts & Rationale 170
- Concepts & Rationale 171
- Concepts & Rationale 172
- Concepts & Rationale 173
- Concepts & Rationale 174
- Concepts & Rationale 175
- Concepts & Rationale 176
- Concepts & Rationale 177
- Concepts & Rationale 178
- Concepts & Rationale 179
- Concepts & Rationale 180
- Concepts & Rationale 181
- Concepts & Rationale 182
- Concepts & Rationale 183
- Concepts & Rationale 186
- Concepts & Rationale 187
- Concepts & Rationale 188
- Concepts & Rationale 189
- Concepts & Rationale 190
- Concepts & Rationale 191
- Concepts & Rationale 192
- Concepts & Rationale 193
- Concepts & Rationale 194
- Concepts & Rationale 195
- Concepts & Rationale 196
- Concepts & Rationale 197
- Concepts & Rationale 198
- Concepts & Rationale 199
- Concepts & Rationale 200
- Concepts & Rationale 201
- Concepts & Rationale 202
- Concepts & Rationale 220
- Concepts & Rationale 221
- Concepts & Rationale 222
- Concepts & Rationale 223
- Concepts & Rationale 224

## God Nodes (most connected - your core abstractions)
1. `ErrorCode` - 64 edges
2. `Session` - 44 edges
3. `Transaction` - 41 edges
4. `Staff` - 38 edges
5. `SessionService` - 35 edges
6. `Item` - 34 edges
7. `Shift` - 34 edges
8. `Member` - 33 edges
9. `ConsoleType` - 33 edges
10. `PaymentService` - 32 edges

## Surprising Connections (you probably didn't know these)
- `Table: refresh_tokens` --semantically_similar_to--> `gamersden.auth / JWT Config`  [INFERRED] [semantically similar]
  backend/src/main/resources/db/migration/V001_1__auth_refresh_tokens.sql → backend/src/main/resources/application.yml
- `Table: print_jobs` --semantically_similar_to--> `Dev Profile Printing Disabled (fake printer port)`  [INFERRED] [semantically similar]
  backend/src/main/resources/db/migration/V001__baseline.sql → backend/src/main/resources/application-dev.yml
- `Table: print_jobs` --semantically_similar_to--> `Venue Profile Printing Enabled (owns USB thermal printer)`  [INFERRED] [semantically similar]
  backend/src/main/resources/db/migration/V001__baseline.sql → backend/src/main/resources/application-venue.yml
- `Table: pricing` --semantically_similar_to--> `gamersden.morning-discount Config (10:00-14:00, 25%)`  [INFERRED] [semantically similar]
  backend/src/main/resources/db/migration/V001__baseline.sql → backend/src/main/resources/application.yml
- `Rationale: Additive-Only Schema Owned by Flyway` --semantically_similar_to--> `Rationale: Schema Owned by Flyway, Never ddl-auto Beyond validate`  [INFERRED] [semantically similar]
  backend/src/main/resources/db/migration/V001__baseline.sql → backend/src/main/resources/application.yml

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **PIN Login Flow** — backend_src_main_java_dev_gamersden_auth_web_authcontroller_login, backend_src_main_java_dev_gamersden_auth_domain_authservice_login, backend_src_main_java_dev_gamersden_auth_domain_jwtservice_issueaccesstoken, backend_src_main_java_dev_gamersden_auth_domain_refreshtokenservice_issue, backend_src_main_java_dev_gamersden_auth_web_refreshcookies_refreshcookies [EXTRACTED 0.90]
- **Refresh Token Rotation & Reuse Detection** — backend_src_main_java_dev_gamersden_auth_web_authcontroller_refresh, backend_src_main_java_dev_gamersden_auth_domain_authservice_refresh, backend_src_main_java_dev_gamersden_auth_domain_refreshtokenservice_rotate, backend_src_main_java_dev_gamersden_auth_repo_refreshtokenrepository_refreshtokenrepository [EXTRACTED 0.85]
- **Stateless JWT Security Chain** — backend_src_main_java_dev_gamersden_auth_config_securityconfig_securityconfig, backend_src_main_java_dev_gamersden_auth_web_jwtauthenticationfilter_jwtauthenticationfilter, backend_src_main_java_dev_gamersden_auth_web_apiauthenticationentrypoint_apiauthenticationentrypoint, backend_src_main_java_dev_gamersden_auth_web_apiaccessdeniedhandler_apiaccessdeniedhandler [EXTRACTED 0.90]
- **Settle a bill: charges → settlement → transaction snapshot → tenders → receipt** — backend_src_main_java_dev_gamersden_billing_domain_paymentservice_settle, backend_src_main_java_dev_gamersden_billing_domain_settlement_of, backend_src_main_java_dev_gamersden_billing_domain_transaction_transaction, backend_src_main_java_dev_gamersden_billing_domain_paymentsplit_paymentsplit, backend_src_main_java_dev_gamersden_billing_domain_settleresult_settleresult [EXTRACTED 0.90]
- **Void a payment: reversal transaction, negated tenders, result** — backend_src_main_java_dev_gamersden_billing_domain_paymentservice_voidpayment, backend_src_main_java_dev_gamersden_billing_domain_transaction_transaction, backend_src_main_java_dev_gamersden_billing_domain_paymentsplit_paymentsplit, backend_src_main_java_dev_gamersden_billing_domain_voidresult_voidresult [EXTRACTED 0.90]
- **GET bill: controller → service orchestration → pure arithmetic → wire view** — backend_src_main_java_dev_gamersden_billing_web_billcontroller_billcontroller, backend_src_main_java_dev_gamersden_billing_domain_billservice_of, backend_src_main_java_dev_gamersden_billing_domain_bill_bill, backend_src_main_java_dev_gamersden_billing_web_billview_billview [EXTRACTED 0.85]
- **Stock movement audit lifecycle (item, movement, reason, writers)** — backend_src_main_java_dev_gamersden_catalog_domain_item_class, backend_src_main_java_dev_gamersden_catalog_domain_stockmovement_class, backend_src_main_java_dev_gamersden_catalog_domain_stockmovementreason_enum, backend_src_main_java_dev_gamersden_catalog_domain_itemservice_applystock, backend_src_main_java_dev_gamersden_catalog_domain_cartsettlementservice_move [INFERRED 0.85]
- **Stock reservation guard vs settlement stock movement** — backend_src_main_java_dev_gamersden_catalog_domain_cartservice_requirestock, backend_src_main_java_dev_gamersden_catalog_repo_cartlinerepository_heldelsewhere, backend_src_main_java_dev_gamersden_catalog_domain_cartsettlementservice_move, backend_src_main_java_dev_gamersden_catalog_domain_item_class [INFERRED 0.80]
- **Batched item-name lookup pattern across describe methods** — backend_src_main_java_dev_gamersden_catalog_domain_cartservice_describe, backend_src_main_java_dev_gamersden_catalog_domain_cartlookupservice_describe, backend_src_main_java_dev_gamersden_catalog_domain_cartsettlementservice_describe [INFERRED 0.75]
- **Ledger-as-source-of-truth balance pattern** — backend_src_main_java_dev_gamersden_member_domain_member_member, backend_src_main_java_dev_gamersden_member_domain_pointsledgerentry_pointsledgerentry, backend_src_main_java_dev_gamersden_member_domain_walletledgerentry_walletledgerentry, backend_src_main_java_dev_gamersden_member_domain_walletservice_walletservice, backend_src_main_java_dev_gamersden_member_domain_membersettlementservice_membersettlementservice [INFERRED 0.85]
- **Wallet top-up / points redemption movement flow** — backend_src_main_java_dev_gamersden_member_domain_walletservice_walletservice, backend_src_main_java_dev_gamersden_member_repo_memberrepository_findbyidforupdate, backend_src_main_java_dev_gamersden_member_repo_walletledgerrepository_walletledgerrepository, backend_src_main_java_dev_gamersden_member_repo_pointsledgerrepository_pointsledgerrepository, backend_src_main_java_dev_gamersden_member_domain_member_member [INFERRED 0.80]
- **Member registration with phone-based deduplication** — backend_src_main_java_dev_gamersden_member_domain_memberservice_memberservice, backend_src_main_java_dev_gamersden_member_domain_phones_phones, backend_src_main_java_dev_gamersden_member_repo_memberrepository_memberrepository, backend_src_main_java_dev_gamersden_member_web_creatememberrequest_creatememberrequest [INFERRED 0.75]
- **Shift close drawer reconciliation** — backend_src_main_java_dev_gamersden_shift_domain_shiftservice_close, backend_src_main_java_dev_gamersden_shift_domain_shiftreportservice_countedreport, backend_src_main_java_dev_gamersden_shift_domain_cashcount_counted, backend_src_main_java_dev_gamersden_shift_domain_shifttakings, backend_src_main_java_dev_gamersden_shift_domain_expensesummary, backend_src_main_java_dev_gamersden_shift_repo_shiftrepository_findopenbyterminalforupdate [EXTRACTED 0.90]
- **Petty-cash expense recording with voucher** — backend_src_main_java_dev_gamersden_shift_web_expensecontroller_record, backend_src_main_java_dev_gamersden_shift_domain_expenseservice_record, backend_src_main_java_dev_gamersden_shift_domain_expense, backend_src_main_java_dev_gamersden_shift_repo_expenserepository, backend_src_main_java_dev_gamersden_shift_web_createexpenserequest [EXTRACTED 0.85]
- **X/Z report computation pipeline** — backend_src_main_java_dev_gamersden_shift_domain_shiftreport, backend_src_main_java_dev_gamersden_shift_domain_shiftreportservice_report, backend_src_main_java_dev_gamersden_shift_domain_shifttakings_of, backend_src_main_java_dev_gamersden_shift_domain_expensesummary_of, backend_src_main_java_dev_gamersden_shift_domain_cashcount, backend_src_main_java_dev_gamersden_shift_domain_methodtakings, backend_src_main_java_dev_gamersden_shift_domain_prorata_across [EXTRACTED 0.85]
- **Floor card state derived from station + live session** — backend_src_main_java_dev_gamersden_station_domain_station_station, backend_src_main_java_dev_gamersden_station_domain_stationstatus_stationstatus, backend_src_main_java_dev_gamersden_station_domain_stationfloorstate_stationfloorstate, backend_src_main_java_dev_gamersden_station_domain_stationsummary_stationsummary, stationsummary_floorstateof, backend_src_main_java_dev_gamersden_common_spi_sessionlookup_sessionlookup [INFERRED 0.85]
- **Rate card quoted live, snapshotted onto blocks at purchase** — backend_src_main_java_dev_gamersden_station_domain_pricing_pricing, backend_src_main_java_dev_gamersden_station_domain_pricingservice_pricingservice, backend_src_main_java_dev_gamersden_station_web_pricingcontroller_pricingcontroller, backend_src_main_java_dev_gamersden_station_domain_stationlookupservice_stationlookupservice, backend_src_main_java_dev_gamersden_station_web_pricingview_pricingview [INFERRED 0.80]
- **Station CRUD plus single-read Floor grid** — backend_src_main_java_dev_gamersden_station_web_stationcontroller_stationcontroller, backend_src_main_java_dev_gamersden_station_domain_stationservice_stationservice, backend_src_main_java_dev_gamersden_station_repo_stationrepository_stationrepository, backend_src_main_java_dev_gamersden_station_web_stationview_stationview, backend_src_main_java_dev_gamersden_station_domain_stationsummary_stationsummary [INFERRED 0.80]
- **Session lifecycle: open, clock, expire, settle** — backend_src_main_java_dev_gamersden_session_domain_sessionservice_open, backend_src_main_java_dev_gamersden_session_domain_sessionservice_clock, backend_src_main_java_dev_gamersden_session_domain_sessionservice_settleexpiry, backend_src_main_java_dev_gamersden_session_domain_sessionservice_end, backend_src_main_java_dev_gamersden_session_domain_sessionexpiryscheduler_sessionexpiryscheduler [INFERRED 0.85]
- **Derived-state-on-read pattern across session readers** — backend_src_main_java_dev_gamersden_session_domain_sessionclock_sessionclock, backend_src_main_java_dev_gamersden_session_domain_sessiondetail_sessiondetail, backend_src_main_java_dev_gamersden_session_domain_sessionlookupservice_sessionlookupservice, backend_src_main_java_dev_gamersden_session_domain_membervisitlookupservice_membervisitlookupservice, backend_src_main_java_dev_gamersden_session_domain_sessionbilllookupservice_sessionbilllookupservice [INFERRED 0.80]
- **Session package's SPI-door services for cross-package reads/writes** — backend_src_main_java_dev_gamersden_session_domain_sessionlookupservice_sessionlookupservice, backend_src_main_java_dev_gamersden_session_domain_membervisitlookupservice_membervisitlookupservice, backend_src_main_java_dev_gamersden_session_domain_sessionbilllookupservice_sessionbilllookupservice, backend_src_main_java_dev_gamersden_session_domain_sessionsettlementservice_sessionsettlementservice [EXTRACTED 0.90]
- **Lookup ports family (narrow reads across bounded contexts)** — backend_src_main_java_dev_gamersden_common_spi_shiftlookup_shiftlookup, backend_src_main_java_dev_gamersden_common_spi_prepaidseatlookup_prepaidseatlookup, backend_src_main_java_dev_gamersden_common_spi_stationlookup_stationlookup, backend_src_main_java_dev_gamersden_common_spi_stationreservation_stationreservation, backend_src_main_java_dev_gamersden_common_spi_sessionlookup_sessionlookup, backend_src_main_java_dev_gamersden_common_spi_membervisitlookup_membervisitlookup, backend_src_main_java_dev_gamersden_common_spi_cartlookup_cartlookup, backend_src_main_java_dev_gamersden_common_spi_memberpointslookup_memberpointslookup, backend_src_main_java_dev_gamersden_common_spi_sessionbilllookup_sessionbilllookup, backend_src_main_java_dev_gamersden_common_spi_tournamentbilllookup_tournamentbilllookup, backend_src_main_java_dev_gamersden_common_spi_shifttakingslookup_shifttakingslookup [INFERRED 0.85]
- **Settlement ports family (narrow writes for settle/void)** — backend_src_main_java_dev_gamersden_common_spi_cartsettlement_cartsettlement, backend_src_main_java_dev_gamersden_common_spi_membersettlement_membersettlement, backend_src_main_java_dev_gamersden_common_spi_sessionsettlement_sessionsettlement [INFERRED 0.85]
- **Printing ports family (render-and-queue print jobs)** — backend_src_main_java_dev_gamersden_common_spi_salereceiptprinting_salereceiptprinting, backend_src_main_java_dev_gamersden_common_spi_expensevoucherprinting_expensevoucherprinting, backend_src_main_java_dev_gamersden_common_spi_shiftreportprinting_shiftreportprinting [INFERRED 0.85]
- **Servlet Filter & Error Rendering Chain** — backend_src_main_java_dev_gamersden_common_trace_traceidfilter_traceidfilter, backend_src_main_java_dev_gamersden_common_trace_requestloggingfilter_requestloggingfilter, backend_src_main_java_dev_gamersden_common_idempotency_idempotencyfilter_idempotencyfilter, backend_src_main_java_dev_gamersden_common_error_globalexceptionhandler_globalexceptionhandler, backend_src_main_java_dev_gamersden_common_error_errorresponsewriter_errorresponsewriter [INFERRED 0.75]
- **Idempotent Request Replay Flow** — backend_src_main_java_dev_gamersden_common_idempotency_idempotencyfilter_idempotencyfilter, backend_src_main_java_dev_gamersden_common_idempotency_idempotencystore_idempotencystore, backend_src_main_java_dev_gamersden_common_idempotency_idempotencykeyrepository_reserve, backend_src_main_java_dev_gamersden_common_idempotency_idempotencykey_idempotencykey, backend_src_main_java_dev_gamersden_common_idempotency_cachedbodyhttpservletrequest_cachedbodyhttpservletrequest [INFERRED 0.85]
- **Error Envelope Taxonomy** — backend_src_main_java_dev_gamersden_common_error_apiexception_apiexception, backend_src_main_java_dev_gamersden_common_error_errorcode_errorcode, backend_src_main_java_dev_gamersden_common_error_errorresponse_errorresponse, backend_src_main_java_dev_gamersden_common_error_globalexceptionhandler_globalexceptionhandler [INFERRED 0.80]
- **Spring Bootstrap Configuration Set** — backend_src_main_java_dev_gamersden_common_config_timeconfig_timeconfig, backend_src_main_java_dev_gamersden_common_config_webmvcconfig_webmvcconfig, backend_src_main_java_dev_gamersden_common_config_openapiconfig_openapiconfig, backend_src_main_java_dev_gamersden_common_config_schedulingconfig_schedulingconfig, backend_src_main_java_dev_gamersden_common_config_gamersdenproperties_gamersdenproperties [INFERRED 0.85]
- **Staff Authentication Principal Model** — backend_src_main_java_dev_gamersden_common_security_currentstaff_currentstaff, backend_src_main_java_dev_gamersden_common_security_staffauthentication_staffauthentication, backend_src_main_java_dev_gamersden_common_security_staffprincipal_staffprincipal, backend_src_main_java_dev_gamersden_common_security_roles_roles [INFERRED 0.85]
- **Venue Precision Policy Set** — backend_src_main_java_dev_gamersden_common_config_venuetime_venuetime, backend_src_main_java_dev_gamersden_common_util_money_money, backend_src_main_java_dev_gamersden_common_config_gamersdenproperties_gamersdenproperties [INFERRED 0.70]
- **Print Job Lifecycle: Render Once, Queue, Retry, Reprint** — backend_src_main_java_dev_gamersden_printing_domain_printjob_printjob, backend_src_main_java_dev_gamersden_printing_domain_printjobstatus_printjobstatus, backend_src_main_java_dev_gamersden_printing_domain_printjobservice_printjobservice, backend_src_main_java_dev_gamersden_printing_domain_receiptrenderer_receiptrenderer, backend_src_main_java_dev_gamersden_printing_domain_rendereddocument_rendereddocument, backend_src_main_java_dev_gamersden_printing_domain_reprintreason_reprintreason [EXTRACTED 0.90]
- **ReceiptRenderer Swap Seam (Placeholder to B17 ESC/POS)** — backend_src_main_java_dev_gamersden_printing_domain_receiptrenderer_receiptrenderer, backend_src_main_java_dev_gamersden_printing_domain_placeholderreceiptrenderer_placeholderreceiptrenderer, backend_src_main_java_dev_gamersden_printing_config_printingconfig_printingconfig [EXTRACTED 0.85]
- **Deferred Work Queues: Print Jobs, Sync Outbox, Alerts** — backend_src_main_java_dev_gamersden_printing_domain_printjob_printjob, backend_src_main_java_dev_gamersden_sync_domain_syncoutboxentry_syncoutboxentry, backend_src_main_java_dev_gamersden_alert_domain_alert_alert [INFERRED 0.65]
- **Deployment Profile Family: Printing & Sync Across dev/cloud/venue/test** — backend_src_main_resources_application_dev_yml_printing, backend_src_main_resources_application_cloud_yml_printing, backend_src_main_resources_application_venue_yml_printing, backend_src_main_resources_application_test_yml_printing, backend_src_main_resources_application_dev_yml_sync, backend_src_main_resources_application_cloud_yml_sync, backend_src_main_resources_application_venue_yml_sync, backend_src_main_resources_application_test_yml_sync [INFERRED 0.75]
- **Venue-to-Cloud Sync Pipeline** — backend_src_main_resources_application_venue_yml_sync, backend_src_main_resources_application_cloud_yml_sync, backend_src_main_resources_db_migration_v001_baseline_table_sync_outbox [INFERRED 0.80]
- **Point-of-Sale Payment Bounded Context** — backend_src_main_resources_db_migration_v001_baseline_table_transactions, backend_src_main_resources_db_migration_v001_baseline_table_payment_splits, backend_src_main_resources_db_migration_v001_baseline_table_carts, backend_src_main_resources_db_migration_v001_baseline_table_cart_lines, backend_src_main_resources_db_migration_v001_baseline_table_points_ledger, backend_src_main_resources_db_migration_v001_baseline_table_wallet_ledger [INFERRED 0.85]

## Communities (225 total, 80 thin omitted)

### Community 0 - "Error Code Taxonomy"
Cohesion: 0.05
Nodes (51): jakarta.validation.ConstraintViolationException, ErrorCode, ALREADY_CHECKED_IN, BLOCKS_CONSUMED, CANCEL_CUTOFF_PASSED, CONFLICT, CONSOLE_TYPE_MISMATCH, DUPLICATE_NAME (+43 more)

### Community 1 - "Bill Assembly & Ports"
Cohesion: 0.07
Nodes (21): Bill, BillLine, BillLineKind, FNB, GAMING, TOURNAMENT, BillService, BillLineView (+13 more)

### Community 2 - "Station Floor & Pricing"
Cohesion: 0.09
Nodes (13): ConsoleType, PS4, PS5, Pricing, PricingService, StationStatus, AVAILABLE, MAINTENANCE (+5 more)

### Community 3 - "Cart & Stock Domain"
Cohesion: 0.08
Nodes (6): Item, ItemCategory, BEVERAGE, EXTRAS, FOOD, SNACK

### Community 4 - "Bill Arithmetic Rules"
Cohesion: 0.07
Nodes (46): Bill, Bill.Member, Bill's three arithmetic rules (unbilled-only, price snapshots, redemption cap), BillLine, BillLineKind, BillService, BillService.of, BillService/Bill split: pure arithmetic vs. lookup orchestration (+38 more)

### Community 5 - "Web Request/Response DTOs"
Cohesion: 0.07
Nodes (21): com.fasterxml.jackson.annotation.JsonInclude, io.swagger.v3.oas.annotations.media.Schema, CreateStaffRequest, LoginRequest, PrefsRequest, PrefsResponse, StaffView, UpdateStaffRequest (+13 more)

### Community 6 - "Session Domain Core"
Cohesion: 0.09
Nodes (13): VenueTime, CurrentStaff, PaidBlocks, SessionSettlement, Override, MemberVisitLookupService, Override, SessionBillLookupService (+5 more)

### Community 7 - "Idempotency Replay Guard"
Cohesion: 0.08
Nodes (15): IdempotencyKey, IdempotencyKeyRepository, IdempotencyReaper, IdempotencyStore, Kind, IN_FLIGHT, MISMATCH, REPLAY (+7 more)

### Community 8 - "Station Lookup Ports"
Cohesion: 0.11
Nodes (8): LiveSession, SessionLookup, Station, Override, StationLookupService, StationService, StationSummary, StationRepository

### Community 9 - "Bean Validation Constraints"
Cohesion: 0.18
Nodes (17): io.swagger.v3.oas.annotations.tags.Tag, MeController, StaffController, BillController, CartController, status(), Roles, ExpenseController (+9 more)

### Community 10 - "Auth Domain Services"
Cohesion: 0.10
Nodes (8): RefreshToken, IssuedToken, Override, Minted, RefreshTokenService, Rotation, RefreshTokenRepository, java.security.SecureRandom

### Community 12 - "Member Domain Core"
Cohesion: 0.09
Nodes (7): Member, MemberLoyaltyLookupService, MemberSettlementService, WalletService, MemberRepository, PointsLedgerRepository, WalletLedgerRepository

### Community 13 - "Filter Chain & Tracing"
Cohesion: 0.14
Nodes (16): ContentCachingResponseWrapper, jakarta.servlet.FilterChain, jakarta.servlet.http.HttpServletRequest, jakarta.servlet.http.HttpServletResponse, Override, Override, Override, IdempotencyFilter (+8 more)

### Community 14 - "Member Settlement Ports"
Cohesion: 0.09
Nodes (10): AlertService, ForbiddenException, AlertPublisher, MemberVisitLookup, Visit, StationLookup, Member, MemberService (+2 more)

### Community 15 - "Print Job & Alert Queue"
Cohesion: 0.07
Nodes (31): Alert, AlertService.raise(), AlertRepository, PlaceholderReceiptRenderer.renderSale(), PlaceholderReceiptRenderer.renderShiftReport(), PrintJob, PrintJobService.issueExpenseVoucher(), PrintJobService.issueSaleReceipt() (+23 more)

### Community 16 - "Shift Report Computation"
Cohesion: 0.15
Nodes (3): Shift, PrintedReport, ShiftService

### Community 17 - "Shift Service & Repos"
Cohesion: 0.12
Nodes (11): Expense, ExpenseCategory, OTHER, REPAIRS, STAFF, SUPPLIES, UTILITIES, ExpenseService (+3 more)

### Community 18 - "Terminal Settings"
Cohesion: 0.08
Nodes (9): FontScale, COMPACT, DEFAULT, LARGE, TerminalSettings, Theme, DARK, LIGHT (+1 more)

### Community 19 - "Validation Failure Paths"
Cohesion: 0.11
Nodes (9): Override, ApiException, RateLimitedException, UnauthorizedException, ShiftLookup, Override, Override, ShiftLookupService (+1 more)

### Community 20 - "Cart Repositories"
Cohesion: 0.20
Nodes (11): CartLookupService, CartService, CartSettlementService, ItemHold, ItemService, CartLineRepository, CartRepository, ItemRepository (+3 more)

### Community 22 - "Session Lifecycle Concepts"
Cohesion: 0.08
Nodes (26): PrepaidSeatLookup, PrepaidSeatLookupService, ClockAction, Session, SessionBalance, SessionBlock, SessionDetail, SessionService.changeBlocks (+18 more)

### Community 23 - "Staff & Member Web Views"
Cohesion: 0.13
Nodes (10): io.swagger.v3.oas.annotations.security.SecurityRequirements, AuthController, Session, SessionResponse, PageResponse, CreateMemberRequest, MemberController, MemberView (+2 more)

### Community 24 - "Money & Charges"
Cohesion: 0.10
Nodes (6): Member, Charges, Settlement, Tender, Tender, Money

### Community 25 - "Receipt Printing Ports"
Cohesion: 0.14
Nodes (11): ExpenseVoucher, ExpenseVoucherPrinting, Line, SaleReceipt, SaleReceiptPrinting, Override, ShiftReport, PrintJobService (+3 more)

### Community 26 - "Session Lookup Services"
Cohesion: 0.11
Nodes (25): MemberVisitLookup, TokenSeq, TokenSeqRepository, MemberVisitLookupService, SessionBillLookupService, SessionClock, SessionExpiryScheduler, SessionLookupService (+17 more)

### Community 27 - "Spring Bootstrap Config"
Cohesion: 0.14
Nodes (14): FilterRegistrationBean, io.swagger.v3.oas.models.OpenAPI, jakarta.annotation.PostConstruct, OpenApiConfig, SchedulingConfig, TimeConfig, IdempotencyConfig, PrintingConfig (+6 more)

### Community 28 - "Transaction Ledger"
Cohesion: 0.10
Nodes (12): PaymentMethod, BKASH, CASH, NAGAD, WALLET, PaymentSplit, VerifyState, FAILED (+4 more)

### Community 29 - "Print Job Rendering"
Cohesion: 0.09
Nodes (6): PrintJob, PrintJobStatus, DONE, FAILED, PRINTING, QUEUED

### Community 30 - "Session Settlement"
Cohesion: 0.10
Nodes (16): ClockAction, PAUSE, RESUME, START, from(), to(), canMoveTo(), legalMoves() (+8 more)

### Community 31 - "Database Schema (Flyway)"
Cohesion: 0.15
Nodes (23): refresh_tokens, alerts, cart_lines, carts, expenses, idempotency_keys, items, members (+15 more)

### Community 32 - "SPI Port Families"
Cohesion: 0.18
Nodes (24): CartLookup, CartSettlement, ExpenseVoucherPrinting, SaleReceiptPrinting, SessionBillLookup, SessionSettlement, ShiftReportPrinting, ShiftTakingsLookup (+16 more)

### Community 33 - "Security Configuration"
Cohesion: 0.13
Nodes (12): AuthProperties, RefreshCookie, RefreshCookies, GamersDenProperties, MorningDiscount, Printing, Sync, GamersDenApplication (+4 more)

### Community 34 - "JWT & Refresh Tokens"
Cohesion: 0.24
Nodes (3): AuthService, Session, Staff

### Community 35 - "Settlement Ports"
Cohesion: 0.15
Nodes (7): ConflictException, NotFoundException, ValidationFailedException, LoyaltyMovement, MemberSettlement, Override, org.springframework.data.domain.Sort

### Community 36 - "Catalog Web Layer"
Cohesion: 0.14
Nodes (9): CartSummary, CartType, COUNTER, SESSION, CartLineRequest, CartLineView, Line, CartView (+1 more)

### Community 37 - "Cash Count & Expenses"
Cohesion: 0.17
Nodes (8): CategoryTotal, ExpenseLine, ExpenseSummary, MethodTakings, ShiftReport, ShiftReport, ShiftReportService, ShiftTakings

### Community 38 - "Item & Stock Movement"
Cohesion: 0.13
Nodes (4): jakarta.persistence.Embeddable, CartLine, CartLineId, Override

### Community 39 - "External Framework Types"
Cohesion: 0.18
Nodes (3): jakarta.persistence.Entity, jakarta.persistence.PreUpdate, jakarta.persistence.Table

### Community 40 - "Takings Lookup Port"
Cohesion: 0.18
Nodes (5): Line, PaymentService, Target, CartSettlement, SettleableCart

### Community 42 - "Transaction Repositories"
Cohesion: 0.15
Nodes (4): Override, TakingsLookupService, TransactionPublicId, TransactionRepository

### Community 43 - "Item Web DTOs"
Cohesion: 0.17
Nodes (5): ItemStock, CreateItemRequest, ItemController, ItemView, UpdateItemRequest

### Community 44 - "Wallet & Points Ledger"
Cohesion: 0.19
Nodes (18): MemberSettlementService.applySale, MemberSettlementService.reverseSale, PointsKind, PointsLedgerEntry, TopupMethod, WalletKind, WalletLedgerEntry, WalletService.redeemPointsToWallet (+10 more)

### Community 45 - "Auth Properties & Beans"
Cohesion: 0.18
Nodes (9): SecurityConfig, JwtService, JwtAuthenticationFilter, javax.crypto.SecretKey, org.springframework.core.env.Environment, org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity, org.springframework.security.config.annotation.web.builders.HttpSecurity, org.springframework.security.config.annotation.web.configuration.EnableWebSecurity (+1 more)

### Community 46 - "Cart Line Domain"
Cohesion: 0.18
Nodes (5): Cart, Line, Override, Opened, Line

### Community 47 - "Member Registration"
Cohesion: 0.18
Nodes (17): Member, MemberProfile, MemberService, MemberVisit, Phones, MemberRepository, CreateMemberRequest, MemberController (+9 more)

### Community 48 - "Item Domain"
Cohesion: 0.13
Nodes (6): StockMovement, StockMovementReason, INITIAL, MANUAL_ADJUST, SALE, VOID

### Community 49 - "Receipt Renderer"
Cohesion: 0.29
Nodes (5): Override, ShiftReport, Tender, PlaceholderReceiptRenderer, RenderedDocument

### Community 50 - "Expense Recording"
Cohesion: 0.18
Nodes (16): Expense, ExpenseCategory, ExpenseService, ExpenseService.of, ExpenseService.record, ExpenseSummary.of, ShiftService.current, ExpenseRepository (+8 more)

### Community 51 - "Alert Domain"
Cohesion: 0.14
Nodes (3): Alert, Override, AlertRepository

### Community 52 - "Member Ledger Repos"
Cohesion: 0.14
Nodes (6): PointsKind, EARN, REDEEM_BILL, REDEEM_WALLET, REVERSAL, PointsLedgerEntry

### Community 53 - "Points Ledger Repos"
Cohesion: 0.14
Nodes (6): WalletKind, POINTS_CONVERSION, REVERSAL, SPEND, TOPUP, WalletLedgerEntry

### Community 54 - "Station Lookup & Floor"
Cohesion: 0.17
Nodes (15): SessionLookup SPI, StationLookup SPI, Station entity, StationLookupService, StationService, StationSummary record, StationRepository, CreateStationRequest (+7 more)

### Community 55 - "Shift Lookup Ports"
Cohesion: 0.19
Nodes (5): Tender, PostedTransaction, ShiftTakingsLookup, Tender, ProRata

### Community 56 - "Auth Error Handling"
Cohesion: 0.30
Nodes (7): com.fasterxml.jackson.databind.ObjectMapper, ApiAccessDeniedHandler, ApiAuthenticationEntryPoint, ErrorResponseWriter, org.springframework.security.web.access.AccessDeniedHandler, org.springframework.security.web.AuthenticationEntryPoint, org.springframework.stereotype.Component

### Community 57 - "Session Web DTOs"
Cohesion: 0.31
Nodes (5): io.swagger.v3.oas.annotations.Operation, BlocksRequest, CreateSessionRequest, SessionController, SessionView

### Community 58 - "Shift Web DTOs"
Cohesion: 0.26
Nodes (8): ShiftController, CategoryView, ExpenseLineView, ExpensesView, MethodView, ShiftReportView, TakingsView, org.springframework.validation.annotation.Validated

### Community 59 - "Bounded Context Overviews"
Cohesion: 0.22
Nodes (13): Alert bounded context, AlertService, Auth bounded context, RefreshToken, Rationale: opaque random refresh token, not a JWT, RefreshTokenService, AlertPublisher, OperatorSignOut (+5 more)

### Community 60 - "Payment Web DTOs"
Cohesion: 0.21
Nodes (5): io.swagger.v3.oas.annotations.Parameter, VoidResult, PaymentController, SettleView, VoidView

### Community 62 - "Prepaid Seat & Token Queue"
Cohesion: 0.29
Nodes (4): PrepaidSeat, PrepaidSeatLookup, Override, PrepaidSeatLookupService

### Community 64 - "Pro-Rata Cash Attribution"
Cohesion: 0.17
Nodes (12): CashCount, ExpenseSummary, MethodTakings, ProRata.across, ProRata.byTruncationLoss, Largest-remainder rounding policy, ShiftReport.asDocument, ShiftTakings (+4 more)

### Community 65 - "Schema Constraints & Indexes"
Cohesion: 0.23
Nodes (12): Check Constraint: payment_splits.method IN (CASH,BKASH,NAGAD,WALLET), Check Constraint: sessions.state IN (OPEN,RUNNING,PAUSED,LOCKED,CLOSED), Unique Index: one_live_session_per_station, Rationale: Prepaid Blocks Reference Original Sale Tx to Avoid Double Payment, Table: members, Table: payment_splits, Table: points_ledger, Table: session_blocks (+4 more)

### Community 66 - "Staff Security Principal"
Cohesion: 0.26
Nodes (4): Override, StaffAuthentication, StaffPrincipal, org.springframework.security.authentication.AbstractAuthenticationToken

### Community 68 - "Print Job Types"
Cohesion: 0.17
Nodes (9): PrintJobType, BOOKING_CONFIRMATION, EXPENSE_VOUCHER, PLAY_TICKET, RECEIPT, TEST, TOURNAMENT_STUB, X_REPORT (+1 more)

### Community 70 - "Item Stock Mutations"
Cohesion: 0.18
Nodes (11): ItemService.applyStock, ItemService.create, ItemService.delete, ItemService.update, StockMovementRepository, CreateItemRequest, ItemController.create, ItemController.delete (+3 more)

### Community 71 - "Shift Service Core"
Cohesion: 0.22
Nodes (11): Shift, Derived cash figures snapshotted only at close, ShiftLookupService, ShiftReportService, ShiftService, ShiftService.history, shift package layering doc, ShiftRepository (+3 more)

### Community 72 - "Station Floor State"
Cohesion: 0.25
Nodes (11): StationFloorState enum, StationStatus enum, StationArrivalView record, StationMatchView record, StationSessionView record, StationView record, Pricing.touch (@PreUpdate), Rationale: floor state is derived, never stored (+3 more)

### Community 73 - "Idempotency Store"
Cohesion: 0.29
Nodes (6): jakarta.servlet.http.HttpServletRequestWrapper, jakarta.servlet.ServletInputStream, CachedBodyHttpServletRequest, Override, java.nio.charset.Charset, ServletInputStream

### Community 74 - "Member Web Layer"
Cohesion: 0.25
Nodes (4): MemberProfile, MemberVisit, MemberDetailView, VisitView

### Community 76 - "Station Web Views"
Cohesion: 0.31
Nodes (3): StationController, StationSessionView, StationView

### Community 77 - "Concepts & Rationale 77"
Cohesion: 0.20
Nodes (10): CartService, CartService.requireStock, CartSettlementService.move, CartSettlementService.reverse, CartSettlementService.settle, StockMovement entity, StockMovementReason enum, CartLineRepository.heldElsewhere (+2 more)

### Community 78 - "Concepts & Rationale 78"
Cohesion: 0.29
Nodes (10): ItemHold, ItemService.menu, ItemService.stockOf, ItemStock, CartLineRepository.heldByItem, CartLineRepository.heldOnOpenCarts, ItemController.get, ItemController.list (+2 more)

### Community 79 - "Concepts & Rationale 79"
Cohesion: 0.38
Nodes (10): MemberPointsLookup, MemberSettlement, MemberLoyaltyLookupService, MemberSettlementService, WalletService, MemberRepository.findByIdForUpdate, Member bounded context, Rationale: ledger is the source of truth (+2 more)

### Community 80 - "Concepts & Rationale 80"
Cohesion: 0.27
Nodes (10): StationReservation, TournamentBillLookup, printing package-info, tournament package-info, A tournament-reserved station refuses walk-in sessions with 409 STATION_RESERVED, Tournament Bill Stub Keeps Section Empty Until B12, Reservation Stub Keeps 409 Branch Dormant Until B12, Tournament bounded context (+2 more)

### Community 81 - "Concepts & Rationale 81"
Cohesion: 0.22
Nodes (10): CashCount.counted, CashCount.interim, ShiftReport, ShiftReportService.countedReport, ShiftReportService.interimReport, ShiftReportService.report, ShiftService.interimReport, ExpenseRepository.findByShiftIdOrderByIdAsc (+2 more)

### Community 82 - "Idempotency 82"
Cohesion: 0.31
Nodes (3): IdempotencyPolicy, Route, org.springframework.util.AntPathMatcher

### Community 83 - "Shift Repos 83"
Cohesion: 0.22
Nodes (3): OperatorSignOut, ShiftRepository, org.springframework.data.jpa.repository.Lock

### Community 84 - "Cross-Context Ports 84"
Cohesion: 0.31
Nodes (7): ExpenseLine, Kind, X, Z, MethodLine, ShiftReport, ShiftReportPrinting

### Community 85 - "Station Domain 85"
Cohesion: 0.20
Nodes (9): StationFloorState, BOOKED, FREE, LOCKED, MAINTENANCE, OPEN, PAUSED, RESERVED (+1 more)

### Community 86 - "Concepts & Rationale 86"
Cohesion: 0.22
Nodes (8): AuthProperties, AuthService.logout(), Rationale: minimal claim set avoids a DB round-trip per request, JwtService, AuthController.login(), AuthController.logout(), RefreshCookies, Rationale: HttpOnly + SameSite=Strict cookie removes need for CSRF

### Community 87 - "Concepts & Rationale 87"
Cohesion: 0.25
Nodes (9): AuthService, AuthService.Session, Rationale: createdAt is DB-owned server-side time, Rationale: PIN is BCrypt hash and never logged, Staff, AuthController, LoginRequest, SessionResponse (+1 more)

### Community 88 - "Concepts & Rationale 88"
Cohesion: 0.22
Nodes (9): ErrorResponseWriter, GlobalExceptionHandler, IdempotencyConfig.FILTER_ORDER, IdempotencyConfig.idempotencyFilter (bean), IdempotencyKey, IdempotencyPolicy, IdempotencyStore, Rationale: ErrorResponseWriter exists for faults GlobalExceptionHandler cannot reach (+1 more)

### Community 89 - "Concepts & Rationale 89"
Cohesion: 0.31
Nodes (9): IdempotencyConfig, IdempotencyFilter, IdempotencyPolicy.guards, RequestLoggingFilter, TraceId, TraceIdFilter, Rationale: idempotency filter sits behind security chain, Rationale: access log never logs bodies (PINs, payment refs must not leak) (+1 more)

### Community 90 - "Auth Domain 90"
Cohesion: 0.22
Nodes (4): StaffRole, ADMIN, CASHIER, MANAGER

### Community 92 - "Concepts & Rationale 92"
Cohesion: 0.29
Nodes (7): Rationale: BCrypt strength 10 matches seeded Admin hash, Rationale: refuse dev placeholder JWT secret under venue/cloud profile, SecurityConfig, ApiAccessDeniedHandler, ApiAuthenticationEntryPoint, JwtAuthenticationFilter, Rationale: filter built by SecurityConfig, not a Spring bean

### Community 93 - "Concepts & Rationale 93"
Cohesion: 0.29
Nodes (8): CartLine entity, CartLineId embeddable key, CartSummary.Line, Item entity, ItemCategory enum, CartLineView, Rationale: category is a closed enum, not a lookup table, Rationale: unit price is a price snapshot

### Community 94 - "Concepts & Rationale 94"
Cohesion: 0.50
Nodes (8): CartLookupService.describe, CartService.describe, CartService.putLine, CartSettlementService.describe, CartLineRepository, ItemRepository, Rationale: one batched query for item names, never one per line, Rationale: a settled cart is closed to new lines

### Community 95 - "Concepts & Rationale 95"
Cohesion: 0.25
Nodes (8): MorningDiscount, Microsecond Truncation Rationale, VenueTime.now(), Money.applyPercentDiscount(), Integer BDT Rounding Rationale, Money util, Pricing.blockPriceAt, Rationale: block price snapshotted at purchase

### Community 96 - "Concepts & Rationale 96"
Cohesion: 0.29
Nodes (8): ApiException, ConflictException.requireConflict, ErrorCode, GlobalExceptionHandler.handleApiException, ServiceUnavailableException.requireUnavailable, Rationale: constructor rejects non-409 codes so a miswired code fails fast in tests, Rationale: error code spellings are a frontend contract, never rename, Rationale: INTERNAL_ERROR is last-resort, never thrown deliberately

### Community 97 - "Concepts & Rationale 97"
Cohesion: 0.25
Nodes (8): IdempotencyKeyRepository.deleteKey, IdempotencyKeyRepository.reserve, IdempotencyStore.begin, IdempotencyStore.isStale, IdempotencyStore.release, Rationale: claim key before running request, Rationale: 5 minute grace window before an in-flight claim is abandoned, Rationale: ON CONFLICT DO NOTHING as atomic arbiter between simultaneous retries

### Community 98 - "Concepts & Rationale 98"
Cohesion: 0.36
Nodes (8): CurrentStaff.find(), CurrentStaff.require(), Roles.ANY_STAFF, Role Permission Matrix Rationale, Roles, StaffAuthentication, Claim-Shaped Principal Rationale, StaffPrincipal

### Community 100 - "Printing Domain 100"
Cohesion: 0.25
Nodes (5): ReprintReason, CUSTOMER_COPY, DAMAGED, DISPUTE, LOST

### Community 102 - "Concepts & Rationale 102"
Cohesion: 0.38
Nodes (7): AuthService.login(), Rationale: noRollbackFor keeps failed-PIN counting durable, AuthService.refresh(), JwtService.issueAccessToken(), StaffService.create(), StaffRepository, AuthController.refresh()

### Community 103 - "Concepts & Rationale 103"
Cohesion: 0.38
Nodes (7): RefreshTokenService.revokeAllForStaff(), StaffService.deactivate(), Rationale: deactivate instead of delete to preserve audit trail, StaffService.update(), CreateStaffRequest, StaffController, UpdateStaffRequest

### Community 104 - "Concepts & Rationale 104"
Cohesion: 0.38
Nodes (7): CartService.Opened record, CartSummary, CartType enum, CartController.putLine, CartLineRequest, CartView, Rationale: PUT sets a line so a retry is idempotent, no increment semantics

### Community 105 - "Concepts & Rationale 105"
Cohesion: 0.33
Nodes (7): GamersDenProperties, Config Secrets Boundary Rationale, clock() bean, Clock Injection Testability Rationale, pinDefaultTimeZone(), TimeConfig, VenueTime.ZONE

### Community 106 - "Concepts & Rationale 106"
Cohesion: 0.29
Nodes (7): CachedBodyHttpServletRequest, IdempotencyFilter.asStorableJson, IdempotencyFilter.execute, IdempotencyKeyRepository.complete, IdempotencyStore.complete, Rationale: re-expose the body already consumed to hash it, Rationale: only 2xx responses stored

### Community 107 - "Concepts & Rationale 107"
Cohesion: 0.33
Nodes (6): PrintingConfig.placeholderReceiptRenderer() bean, PrintingConfig, PlaceholderReceiptRenderer, ReceiptRenderer, Placeholder Renderer as Stub Until B17, ReceiptRenderer as Swap Seam for B17

### Community 108 - "Concepts & Rationale 108"
Cohesion: 0.43
Nodes (7): Pricing entity, PricingService, PricingRepository, PricingController, UpdatePricingRequest, PricingView.of, Rationale: pricing edits reach new blocks only

### Community 109 - "Concepts & Rationale 109"
Cohesion: 0.29
Nodes (7): Cloud Profile Refresh Cookie Secure=true Override, Rationale: Refresh Cookie Must Never Travel Unencrypted Over HTTPS, Venue Profile Refresh Cookie Secure=true Override, Rationale: Refresh Cookie Must Never Travel Unencrypted Over HTTPS, gamersden.auth / JWT Config, Rationale: Auth Numbers Fixed by api-contract.md, Not Tunable Per Environment, Rationale: JWT Secret Dev Placeholder Must Be Overridden in Real Deployments

### Community 110 - "Concepts & Rationale 110"
Cohesion: 0.38
Nodes (7): Cloud Profile Printing Disabled, Rationale: Cloud Mirror Has No Printer, Receives Venue Sync Pushes, Cloud Profile Sync (receive-enabled, no push), Venue Profile Printing Enabled (owns USB thermal printer), Rationale: Cafe PC Owns Printer, Pushes Sync to Cloud, Venue Profile Sync (push-enabled, 30s interval), Table: sync_outbox

### Community 111 - "Concepts & Rationale 111"
Cohesion: 0.29
Nodes (7): Dev Profile Logging Override (human-readable console), Dev Profile Printing Disabled (fake printer port), Rationale: Local Dev Uses Fake Printer, Seeded Data, Readable Logs, Test Profile Logging (readable console, root WARN), Test Profile Printing Disabled (fake port), Rationale: Test Profile Uses Testcontainers Postgres, Fake Printer, Base Structured Logging Config

### Community 112 - "Concepts & Rationale 112"
Cohesion: 0.29
Nodes (7): Check Constraint: items.category IN (BEVERAGE,FOOD,SNACK,EXTRAS), Rationale: Item Categories Are a CHECK Enum, Not a Table, Rationale: Tournament Entries/Play Tickets Are Not Items Rows, Table: cart_lines, Table: carts, Table: items, Table: stock_movements

### Community 113 - "Concepts & Rationale 113"
Cohesion: 0.33
Nodes (7): Check Constraint: staff.role IN (ADMIN,MANAGER,CASHIER), Unique Index: one_open_shift_per_terminal, Rationale: Bootstrap PIN Must Change on First Login, PINs Never Logged, Seed Row: Bootstrap Admin Staff (PIN 1234), Table: expenses, Table: shifts, Table: staff

### Community 114 - "Tournament (stub) 114"
Cohesion: 0.33
Nodes (3): StationReservation, Override, TournamentReservationLookup

### Community 115 - "Member Domain 115"
Cohesion: 0.33
Nodes (5): TopupMethod, BKASH, CASH, NAGAD, TopupRequest

### Community 117 - "Concepts & Rationale 117"
Cohesion: 0.33
Nodes (6): RefreshTokenService.issue(), Rationale: one-shot rotation, replay treated as theft, RefreshTokenService.rotate(), Rationale: sign-out is terminal-scoped, not account-wide, RefreshTokenService.signOutOfTerminal(), RefreshTokenRepository

### Community 118 - "Concepts & Rationale 118"
Cohesion: 0.33
Nodes (5): Rationale: ADMIN is seeded bootstrap, not API-hireable, StaffService, MeController, PrefsRequest, PrefsResponse

### Community 119 - "Concepts & Rationale 119"
Cohesion: 0.33
Nodes (6): CartLookupService.unsettledCart, CartService.open, CartRepository, CartController.open, CreateCartRequest, Rationale: unique session cart makes open idempotent

### Community 120 - "Concepts & Rationale 120"
Cohesion: 0.33
Nodes (6): IdempotencyFilter.doFilterInternal, IdempotencyFilter.fingerprint, IdempotencyFilter.replay, IdempotencyStore.Outcome, Rationale: fingerprint = method + path + body, so same key on different route is a mismatch, Rationale: 256KB cap because guarded payloads are small JSON

### Community 121 - "Concepts & Rationale 121"
Cohesion: 0.40
Nodes (6): ShiftLookupService.openShiftId, ShiftService.open, ShiftRepository.findByTerminalAndClosedAtIsNull, OpenShiftRequest, ShiftController.open, Double-guarded one-open-shift-per-terminal invariant

### Community 122 - "Concepts & Rationale 122"
Cohesion: 0.40
Nodes (6): ShiftService.close, ShiftService.raiseDiscrepancyAlert, ShiftRepository.findOpenByTerminalForUpdate, CloseShiftRequest, ShiftController.close, Close writes snapshot, print job, alert and sign-out atomically

### Community 123 - "Concepts & Rationale 123"
Cohesion: 0.40
Nodes (6): Index: refresh_tokens (expires_at), Partial Index: refresh_tokens (staff_id, terminal) WHERE revoked_at IS NULL, Rationale: Only SHA-256 Hash of Cookie Stored, Never Raw Value, Rationale: V001.1 Point-Release Numbering Keeps Migrations Ascending, Rationale: Reuse Detection Revokes Whole Token Family, Table: refresh_tokens

### Community 124 - "Concepts & Rationale 124"
Cohesion: 0.40
Nodes (6): Check Constraint: print_jobs.type enum, Check Constraint: reprint_needs_reason, Rationale: Token Allocation Serialized by Row Lock, Keyed by Date, Rationale: Booking-Era Columns/Print Types Ship Here as Verbatim Doc Copy, Table: print_jobs, Table: token_seq (daily queue-token counter)

### Community 125 - "App Configuration 125"
Cohesion: 0.47
Nodes (4): Override, WebMvcConfig, org.springframework.web.servlet.config.annotation.PathMatchConfigurer, org.springframework.web.servlet.config.annotation.WebMvcConfigurer

### Community 126 - "Concepts & Rationale 126"
Cohesion: 0.50
Nodes (5): gamersden.morning-discount Config (10:00-14:00, 25%), Rationale: Morning Discount is an OPEN FLAG Pending Venue Confirmation, Rationale: Morning Discount Defaults Are OPEN FLAG Pending Venue Confirmation, Seed Rows: PS5/PS4 Console Pricing, Table: pricing

### Community 129 - "Concepts & Rationale 129"
Cohesion: 0.50
Nodes (4): CartSettlementService, ItemService, Rationale: MANDATORY propagation ties stock move to the enclosing payment/void, Rationale: stock never moves without an audit row

### Community 130 - "Concepts & Rationale 130"
Cohesion: 0.50
Nodes (4): gamersDenOpenApi() bean, API_BASE_PATH, API Prefix Convention Rationale, WebMvcConfig

### Community 131 - "Concepts & Rationale 131"
Cohesion: 0.67
Nodes (4): ErrorResponse, ErrorResponseWriter.write, GlobalExceptionHandler.envelope, TraceId.current

### Community 132 - "Concepts & Rationale 132"
Cohesion: 0.50
Nodes (4): IdempotencyKeyRepository.deleteCreatedBefore, IdempotencyReaper.purgeExpiredKeys, IdempotencyStore.purgeExpired, Rationale: reaper is only housekeeping, expiry already enforced on read

### Community 133 - "Concepts & Rationale 133"
Cohesion: 0.67
Nodes (4): Test Profile JPA ddl-auto=validate (restated), Base JPA/Hibernate Config (ddl-auto=validate), Rationale: Schema Owned by Flyway, Never ddl-auto Beyond validate, Rationale: Additive-Only Schema Owned by Flyway

### Community 134 - "Concepts & Rationale 134"
Cohesion: 0.50
Nodes (4): Rationale: Tests Drive Session Expiry Sweep Manually to Avoid Races, Test Profile Lock-Sweeper Disabled (manual sweep in tests), gamersden.sessions Config (lock-sweeper-enabled), Rationale: Lock-Sweeper Persists LOCKED Flip on Idle Floor

### Community 135 - "Concepts & Rationale 135"
Cohesion: 0.67
Nodes (3): Sync, SchedulingConfig, Scheduling Scope Rationale

### Community 136 - "Concepts & Rationale 136"
Cohesion: 0.67
Nodes (3): VenueTime util, PricingService.blockPrice, StationLookupService.blockPriceAt

### Community 137 - "Concepts & Rationale 137"
Cohesion: 0.67
Nodes (3): ForbiddenException, GlobalExceptionHandler.handleAccessDenied, Rationale: @PreAuthorize denials bypass the security filter chain's own handler

### Community 138 - "Concepts & Rationale 138"
Cohesion: 0.67
Nodes (3): GamersDenApplication, Common shared kernel, common.spi package-info

## Ambiguous Edges - Review These
- `Tender` → `TakingsLookupService`  [AMBIGUOUS]
  backend/src/main/java/dev/gamersden/billing/domain/TakingsLookupService.java · relation: references
- `MemberService` → `MemberLoyaltyLookupService`  [AMBIGUOUS]
  backend/src/main/java/dev/gamersden/member/domain/MemberService.java · relation: conceptually_related_to
- `TournamentReservationLookup` → `PrintJobType`  [AMBIGUOUS]
  backend/src/main/java/dev/gamersden/printing/domain/PrintJobType.java · relation: conceptually_related_to
- `TournamentBillLookupService` → `PrintJobType`  [AMBIGUOUS]
  backend/src/main/java/dev/gamersden/printing/domain/PrintJobType.java · relation: conceptually_related_to
- `GamersDenApplication` → `common.spi package-info`  [AMBIGUOUS]
  backend/src/main/java/dev/gamersden/GamersDenApplication.java · relation: conceptually_related_to
- `ForbiddenException` → `GlobalExceptionHandler.handleAccessDenied`  [AMBIGUOUS]
  backend/src/main/java/dev/gamersden/common/error/GlobalExceptionHandler.java · relation: conceptually_related_to
- `printing package-info` → `tournament package-info`  [AMBIGUOUS]
  backend/src/main/java/dev/gamersden/tournament/package-info.java · relation: conceptually_related_to
- `Table: session_blocks` → `Table: transactions`  [AMBIGUOUS]
  backend/src/main/resources/db/migration/V001__baseline.sql · relation: references
- `Table: stock_movements` → `Table: transactions`  [AMBIGUOUS]
  backend/src/main/resources/db/migration/V001__baseline.sql · relation: references
- `Table: transactions` → `Table: points_ledger`  [AMBIGUOUS]
  backend/src/main/resources/db/migration/V001__baseline.sql · relation: references
- `Table: transactions` → `Table: wallet_ledger (LIKE points_ledger)`  [AMBIGUOUS]
  backend/src/main/resources/db/migration/V001__baseline.sql · relation: references

## Knowledge Gaps
- **351 isolated node(s):** `ADMIN`, `MANAGER`, `CASHIER`, `GAMING`, `FNB` (+346 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **80 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Tender` and `TakingsLookupService`?**
  _Edge tagged AMBIGUOUS (relation: references) - confidence is low._
- **What is the exact relationship between `MemberService` and `MemberLoyaltyLookupService`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `TournamentReservationLookup` and `PrintJobType`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `TournamentBillLookupService` and `PrintJobType`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `GamersDenApplication` and `common.spi package-info`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `ForbiddenException` and `GlobalExceptionHandler.handleAccessDenied`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `printing package-info` and `tournament package-info`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._