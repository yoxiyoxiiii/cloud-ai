"""全局异常处理器（spec D4 / 契约 §2）：三 handler 全部 HTTP 200 + R body。"""

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.response import CODE_PARAM_ERROR, CODE_SYSTEM_ERROR, MSG_SYSTEM_ERROR, R

logger = logging.getLogger(__name__)


def register_exception_handlers(app: FastAPI) -> None:
    """向 app 注册三个全局异常 handler（BusinessException / 参数校验 / 未知异常兜底）。"""

    @app.exception_handler(BusinessException)
    async def business_exception_handler(request: Request, exc: BusinessException) -> JSONResponse:
        # 业务异常 warn 级审计（spec §5 第 5 条）
        logger.warning("business exception: %s %s -> code=%s msg=%s",
                       request.method, request.url.path, exc.code, exc.msg)
        body = R.fail(exc.msg, exc.code)
        return JSONResponse(status_code=200, content=body.model_dump())

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request,
                                           exc: RequestValidationError) -> JSONResponse:
        # "loc 点路径 + 空格 + msg + 分号空格" 拼接，对齐 Java MethodArgumentNotValidException 口径
        detail = "".join(
            ".".join(str(loc) for loc in error["loc"]) + " " + error["msg"] + "; "
            for error in exc.errors()
        )
        logger.warning("param validation failed: %s %s -> %s",
                       request.method, request.url.path, detail)
        body = R.fail(detail, CODE_PARAM_ERROR)
        return JSONResponse(status_code=200, content=body.model_dump())

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        # 系统异常 error 级留痕（log.exception 含堆栈），响应不泄细节
        logger.exception("unhandled exception: %s %s", request.method, request.url.path)
        body = R.fail(MSG_SYSTEM_ERROR, CODE_SYSTEM_ERROR)
        return JSONResponse(status_code=200, content=body.model_dump())
