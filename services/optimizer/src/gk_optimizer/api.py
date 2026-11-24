from contextlib import asynccontextmanager
import logging
import secrets
from typing import Any
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from gk_optimizer import solver
from gk_optimizer.settings import Settings

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.settings = Settings.from_env()
    yield


app = FastAPI(title='GK Dynamic Pickup & Delivery', version='2.0', lifespan=lifespan)


@app.middleware('http')
async def internal_access(request: Request, call_next):
    if not (request.url.path == '/health' and request.method in {'GET', 'HEAD'}):
        supplied = request.headers.get('X-Internal-Token', '').encode('utf-8')
        if not secrets.compare_digest(request.app.state.settings.internal_token, supplied):
            return JSONResponse(status_code=403, content={'message': 'Internal service authentication required'})
    return await call_next(request)


class SolveRequest(BaseModel):
    warehouses: list[dict[str, Any]]
    vehicles: list[dict[str, Any]]
    routes: list[dict[str, Any]] = Field(default_factory=list)
    orders: list[dict[str, Any]] = Field(default_factory=list)
    now_min: float
    seconds: float = Field(default=3, ge=.05, le=10, allow_inf_nan=False)
    max_stops: int = Field(default=101, ge=2, le=101)
    max_orders: int = Field(default=45, ge=1, le=45)
    group_deliveries: bool = False
    fixed_trip_cost: float | None = Field(default=None, ge=0, le=1_000_000_000, allow_inf_nan=False)


@app.api_route('/health', methods=['GET', 'HEAD'])
def health():
    return {'status': 'ok'}


@app.post('/solve')
def solve(req: SolveRequest, request: Request):
    body = req.model_dump()
    if body['fixed_trip_cost'] is None:
        body['fixed_trip_cost'] = request.app.state.settings.fixed_trip_cost
    try:
        return solver.solve(body)
    except Exception as error:
        # Log the failure class, without tokens, request bodies or provider secrets.
        logger.warning('Optimization failed (%s)', type(error).__name__)
        raise HTTPException(status_code=503, detail='Routing provider is unavailable; the previous route was preserved') from None
