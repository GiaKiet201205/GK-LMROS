import { useSearchParams } from 'react-router-dom'
import { PageHead } from '../../../shared/components/ui.jsx'
import { useWarehouseFilter as useFilter } from '../context/WarehouseFilterContext.js'
import JourneyBoard from '../components/JourneyBoard.jsx'
export default function Live() {
  const { warehouse } = useFilter()
  const [params] = useSearchParams()
  return <div className="page"><PageHead title="Theo dõi hành trình xe" />
    <JourneyBoard warehouse={warehouse} vehicleId={params.get('vehicle')} />
  </div>
}
