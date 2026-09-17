import torch
import torch.nn as nn


class HifiganInferenceWrapper(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, mel: torch.Tensor) -> torch.Tensor:
        return self.model.inference(mel)
