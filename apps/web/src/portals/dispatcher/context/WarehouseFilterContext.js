import { createContext, useContext } from 'react'

export const WarehouseFilterContext = createContext({ warehouse: '' })
export const useWarehouseFilter = () => useContext(WarehouseFilterContext)
