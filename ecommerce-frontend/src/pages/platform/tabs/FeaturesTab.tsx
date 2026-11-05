import { TenantDetails } from "../../../redux/api/platformAPI";

// Replaced in the next commit.
const FeaturesTab = ({ details }: { details: TenantDetails }) => <p data-store={details.tenant.slug}>Coming in the next task.</p>;

export default FeaturesTab;
