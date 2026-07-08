import axios from 'axios'

const api = axios.create({
  baseURL: 'api',
  withCredentials: true,
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    // 401 = not authenticated -> bounce to login. 403 = authenticated but not
    // authorised -> the caller renders "access denied" and stays on the page
    // (redirecting on 403 turned a permission error into a login bounce).
    if (error.response?.status === 401) {
      window.location.hash = '#/login'
    }
    return Promise.reject(error)
  },
)

export default api
