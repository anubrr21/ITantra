import torch
import torch.nn as nn
import torch.nn.functional as F

PRE_EMPHASIS = 0.9700000286102295
N_FFT = 512
HOP_LENGTH = 160
WIN_LENGTH = 400
LOG_EPS = 5.9604644775390625e-08
STD_EPS = 1.0e-05


class MelSpectrogramPreprocessor(nn.Module):
    def __init__(self, window: torch.Tensor, mel_filterbank: torch.Tensor):
        super().__init__()
        self.register_buffer("window", window)
        self.register_buffer("mel_filterbank", mel_filterbank)

    def forward(self, input_signal: torch.Tensor, length: torch.Tensor):
        lengths = torch.div(length, HOP_LENGTH, rounding_mode="floor") + 1

        padded = F.pad(input_signal, (1, 0))
        waveform = input_signal - PRE_EMPHASIS * padded[:, :-1]

        framed = F.pad(waveform.unsqueeze(1), (N_FFT // 2, N_FFT // 2), mode="reflect").squeeze(1)
        stft = torch.stft(
            framed,
            n_fft=N_FFT,
            hop_length=HOP_LENGTH,
            win_length=WIN_LENGTH,
            window=self.window,
            center=False,
            return_complex=False,
        )
        power = stft[..., 0] ** 2 + stft[..., 1] ** 2

        features = torch.matmul(power.transpose(-1, -2), self.mel_filterbank).transpose(-1, -2)
        features = torch.log(features + LOG_EPS)

        time_idx = torch.arange(features.size(-1), device=features.device).repeat(lengths.size(0), 1)
        mask = (time_idx < lengths.view(-1, 1)).unsqueeze(1)
        inv_mask = ~mask

        features = features.masked_fill(inv_mask, 0.0)
        means = features.sum(dim=2, keepdim=True) / lengths.view(-1, 1, 1)
        centered = features - means
        variance = centered.masked_fill(inv_mask, 0.0).pow(2).sum(dim=2, keepdim=True) / (lengths.view(-1, 1, 1) - 1)
        stds = torch.sqrt(variance.clamp(min=LOG_EPS))
        normalized = centered / (stds + STD_EPS)
        normalized = normalized.masked_fill(inv_mask, 0.0)

        return normalized, lengths.to(torch.int64)
