export type HttpMethod = "GET" | "POST" | "PATCH" | "PUT" | "DELETE";

export type ApiError = {
  status: number;
  code?: string;
  message: string;
  fieldErrors?: Record<string, string>;
};

export type ApiDownload = { blob: Blob; filename: string };

function readCookie(name: string): string | undefined {
  const entry = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${name}=`));
  return entry ? decodeURIComponent(entry.slice(name.length + 1)) : undefined;
}

async function readError(response: Response): Promise<ApiError> {
  const body = (await response.json().catch(() => ({}))) as {
    code?: string;
    message?: string;
    fieldErrors?: Record<string, string>;
  };
  return {
    status: response.status,
    code: body.code,
    message: body.message ?? "Không thể hoàn tất yêu cầu. Vui lòng thử lại.",
    fieldErrors: body.fieldErrors,
  };
}

export async function apiRequest<T>(
  path: string,
  method: HttpMethod = "GET",
  body?: unknown,
): Promise<T> {
  const headers = new Headers({ Accept: "application/json" });
  if (body !== undefined && !(body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  const csrf = readCookie("XSRF-TOKEN");
  if (method !== "GET" && csrf) headers.set("X-XSRF-TOKEN", csrf);

  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      credentials: "include",
      headers,
      body:
        body === undefined
          ? undefined
          : body instanceof FormData
            ? body
            : JSON.stringify(body),
    });
  } catch {
    throw {
      status: 0,
      message: "Chưa kết nối được máy chủ. Kiểm tra backend rồi thử lại.",
    } satisfies ApiError;
  }

  if (!response.ok) throw await readError(response);
  if (response.status === 204) return undefined as T;
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) return (await response.json()) as T;
  return (await response.blob()) as T;
}

export async function apiDownload(path: string, expectedMimeType: string): Promise<ApiDownload> {
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method: "GET",
      credentials: "include",
      headers: { Accept: expectedMimeType },
    });
  } catch {
    throw { status: 0, message: "Chưa kết nối được máy chủ. Kiểm tra backend rồi thử lại." } satisfies ApiError;
  }
  if (!response.ok) throw await readError(response);

  const actualMimeType = (response.headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();
  if (actualMimeType !== expectedMimeType.toLowerCase()) {
    throw { status: response.status, message: "Máy chủ trả về định dạng tệp không khớp yêu cầu." } satisfies ApiError;
  }
  const disposition = response.headers.get("content-disposition") ?? "";
  if (!/attachment/i.test(disposition)) {
    throw { status: response.status, message: "Phản hồi tải xuống thiếu tên tệp đính kèm hợp lệ." } satisfies ApiError;
  }
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1];
  let filename = encoded ? decodeURIComponent(encoded) : plain ?? "";
  filename = filename.replace(/[\\/\u0000-\u001f]/g, "").trim();
  if (!filename || filename === "." || filename === "..") {
    throw { status: response.status, message: "Máy chủ không cung cấp tên tệp hợp lệ." } satisfies ApiError;
  }
  return { blob: await response.blob(), filename };
}

export async function requestCsrf(): Promise<void> {
  await apiRequest<unknown>("/auth/csrf");
}

export function errorMessage(error: unknown): string {
  if (typeof error === "object" && error !== null && "message" in error) {
    return String((error as ApiError).message);
  }
  return "Đã xảy ra lỗi không xác định.";
}
