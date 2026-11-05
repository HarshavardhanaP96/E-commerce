import { TenantDetails } from "../../../redux/api/platformAPI";

// Replaced in the next commit.
const Stub = ({ details }: { details: TenantDetails }) => <p data-store={details.tenant.slug}>Coming in the next task.</p>;

export const PaymentsTab = Stub;
export const EmailTab = Stub;
export const LegalTab = Stub;
