library(TSA)
data(arma11.s)

z <- arma11.s


ar.max = 7
ma.max = 13

lag1 <- function(z, lag = 1) {
  c(rep(NA, lag), z[1:(length(z) - lag)])
}
reupm <- function(m1, nrow, ncol) {
  k <- ncol - 1
  m2 <- NULL
  for (i in 1:k) {
    i1 <- i + 1
    work <- lag1(m1[, i])
    work[1] <- -1
    temp <- m1[, i1] - work * m1[i1, i1]/m1[i, i]
    temp[i1] <- 0
    m2 <- cbind(m2, temp)
  }
  m2
}
ceascf <- function(m, cov1, nar, ncol, count, ncov, z, zm) {
  result <- 0 * seq(1, nar + 1)
  result[1] <- cov1[ncov + count]
  for (i in 1:nar) {
    temp <- cbind(z[-(1:i)], zm[-(1:i), 1:i]) %*% c(1, 
                                                    -m[1:i, i])
    result[i + 1] <- acf(temp, plot = FALSE, lag.max = count, 
                         drop.lag.0 = FALSE)$acf[count + 1]
  }
  result
}
ar.max <- ar.max + 1
ma.max <- ma.max + 1
nar <- ar.max - 1
nma <- ma.max
ncov <- nar + nma + 2
nrow <- nar + nma + 1
ncol <- nrow - 1
z <- z - mean(z)
zm <- NULL
for (i in 1:nar) zm <- cbind(zm, lag1(z, lag = i))
cov1 <- acf(z, lag.max = ncov, plot = FALSE, drop.lag.0 = FALSE)$acf
cov1 <- c(rev(cov1[-1]), cov1)
ncov <- ncov + 1
m1 <- matrix(0, ncol = ncol, nrow = nrow)
for (i in 1:ncol) m1[1:i, i] <- ar.ols(z, order.max = i, 
                                       aic = FALSE, demean = FALSE, intercept = FALSE)$ar
eacfm <- NULL
for (i in 1:nma) {
  m2 <- reupm(m1 = m1, nrow = nrow, ncol = ncol)
  ncol <- ncol - 1
  eacfm <- cbind(eacfm, ceascf(m2, cov1, nar, ncol, i, 
                               ncov, z, zm))
  m1 <- m2
}
work <- 1:(nar + 1)
work <- length(z) - work + 1
symbol <- NULL
for (i in 1:nma) {
  work <- work - 1
  symbol <- cbind(symbol, ifelse(abs(eacfm[, i]) > 2/work^0.5, 
                                 "x", "o"))
}
rownames(symbol) <- 0:(ar.max - 1)
colnames(symbol) <- 0:(ma.max - 1)
cat("AR/MA\n")
print(symbol, quote = FALSE)
invisible(list(eacf = eacfm, ar.max = ar.max, ma.ma = ma.max, 
               symbol = symbol))


eacf(arma11.s)



dt <- c(
    22.93,
    15.45,
    12.61,
    12.84,
    15.38,
    13.43,
    11.58,
    15.1,
    14.87,
    14.9,
    15.22,
    16.11,
    18.65,
    17.75,
    18.3,
    18.68,
    19.44,
    20.07,
    21.34,
    20.31,
    19.53,
    19.86,
    18.85,
    17.27,
    17.13,
    16.8,
    16.2,
    17.86,
    17.42,
    16.53,
    15.5,
    15.52,
    14.54,
    13.77,
    14.14,
    16.38,
    18.02,
    17.94,
    19.48,
    21.07,
    20.12,
    20.05,
    19.78,
    18.58,
    19.59,
    20.1,
    19.86,
    21.1,
    22.86,
    22.11,
    20.39,
    18.43,
    18.2,
    16.7,
    18.45,
    27.31,
    33.51,
    36.04,
    32.33,
    27.28,
    25.23,
    20.48,
    19.9,
    20.83,
    21.23,
    20.19,
    21.4,
    21.69,
    21.89,
    23.23,
    22.46,
    19.5,
    18.79,
    19.01,
    18.92,
    20.23,
    20.98,
    22.38,
    21.77,
    21.34,
    21.88,
    21.68,
    20.34,
    19.41,
    19.03,
    20.09,
    20.32,
    20.25,
    19.95,
    19.09,
    17.89,
    18.01,
    17.5,
    18.15,
    16.61,
    14.51,
    15.03,
    14.78,
    14.68,
    16.42,
    17.89,
    19.06,
    19.65,
    18.38,
    17.45,
    17.72,
    18.07,
    17.16,
    18.04,
    18.57,
    18.54,
    19.9,
    19.74,
    18.45,
    17.32,
    18.02,
    18.23,
    17.43,
    17.99,
    19.03,
    18.85,
    19.09,
    21.33,
    23.5,
    21.16,
    20.42,
    21.3,
    21.9,
    23.97,
    24.88,
    23.7,
    25.23,
    25.13,
    22.18,
    20.97,
    19.7,
    20.82,
    19.26,
    19.66,
    19.95,
    19.8,
    21.32,
    20.19,
    18.33,
    16.72,
    16.06,
    15.12,
    15.35,
    14.91,
    13.72,
    14.17,
    13.47,
    15.03,
    14.46,
    13,
    11.35,
    12.51,
    12.01,
    14.68,
    17.31,
    17.72,
    17.92,
    20.1,
    21.28,
    23.8,
    22.69,
    25,
    26.1,
    27.26,
    29.37,
    29.84,
    25.72,
    28.79,
    31.82,
    29.7,
    31.26,
    33.88,
    33.11,
    34.42,
    28.44,
    29.59,
    29.61,
    27.24,
    27.49,
    28.63,
    27.6,
    26.42,
    27.37,
    26.2,
    22.17,
    19.64,
    19.39,
    19.71,
    20.72,
    24.53,
    26.18,
    27.04,
    25.52,
    26.97,
    28.39,
    29.66,
    28.84,
    26.35,
    29.46,
    32.95,
    35.83,
    33.51,
    28.17,
    28.11,
    30.66,
    30.75,
    31.57,
    28.31,
    30.34,
    31.11,
    32.13,
    34.31,
    34.68,
    36.74,
    36.75,
    40.27,
    38.02,
    40.78,
    44.9,
    45.94,
    53.28,
    48.47,
    43.15,
    46.84,
    48.15,
    54.19,
    52.98,
    49.83,
    56.35,
    58.99,
    64.98,
    65.59,
    62.26,
    58.32,
    59.41,
    65.48
  )


print(diff(log(dt)))
for(i in diff(log(dt))) {
  print(i)
}

library(TSA)
eacf(diff(log(dt)))





